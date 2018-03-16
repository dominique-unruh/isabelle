/*  Title:      Pure/Thy/thy_resources.scala
    Author:     Makarius

PIDE resources for theory files.
*/

package isabelle


object Thy_Resources
{
  /* PIDE session */

  def start_session(
    options: Options,
    session_name: String,
    session_dirs: List[Path] = Nil,
    session_base: Option[Sessions.Base] = None,
    print_mode: List[String] = Nil,
    progress: Progress = No_Progress,
    log: Logger = No_Logger): Session =
  {
    val base =
      session_base getOrElse
      Sessions.base_info(options, session_name, progress = progress, dirs = session_dirs).check_base
    val resources = new Thy_Resources(base, log = log)
    val session = new Session(options, resources)

    val session_error = Future.promise[String]
    var session_phase: Session.Consumer[Session.Phase] = null
    session_phase =
      Session.Consumer(getClass.getName) {
        case Session.Ready =>
          session.phase_changed -= session_phase
          session_error.fulfill("")
        case Session.Terminated(result) if !result.ok =>
          session.phase_changed -= session_phase
          session_error.fulfill("Session start failed: return code " + result.rc)
        case _ =>
      }
    session.phase_changed += session_phase

    progress.echo("Starting " + session_name + " ...")
    Isabelle_Process.start(session, options,
      logic = session_name, dirs = session_dirs, modes = print_mode)

    session_error.join match {
      case "" => session
      case msg => session.stop(); error(msg)
    }
  }

  sealed case class Theories_Result(
    val requirements: List[Document.Node.Name],
    val version: Document.Version,
    val state: Document.State)
  {
  }

  class Session private[Thy_Resources](
    session_options: Options,
    override val resources: Thy_Resources) extends isabelle.Session(session_options, resources)
  {
    session =>

    def use_theories(
      theories: List[(String, Position.T)],
      qualifier: String = Sessions.DRAFT,
      master_dir: String = "",
      progress: Progress = No_Progress): Theories_Result =
    {
      val requirements =
        resources.load_theories(session, theories, qualifier = qualifier,
          master_dir = master_dir, progress = progress)

      val result = Future.promise[Theories_Result]

      def check_state
      {
        val state = session.current_state()
        state.stable_tip_version match {
          case Some(version) if requirements.forall(state.node_consolidated(version, _)) =>
            try { result.fulfill(Theories_Result(requirements, version, state)) }
            catch { case _: IllegalStateException => }
          case _ =>
        }
      }

      val consumer =
        Session.Consumer[Session.Commands_Changed](getClass.getName) {
          case changed => if (requirements.exists(changed.nodes)) check_state
        }

      session.commands_changed += consumer
      check_state
      result.join
      session.commands_changed -= consumer
      result.join
    }
  }


  /* internal state */

  sealed case class State(theories: Map[Document.Node.Name, Theory] = Map.empty)

  final class Theory private[Thy_Resources](
    val node_name: Document.Node.Name,
    val node_header: Document.Node.Header,
    val text: String)
  {
    override def toString: String = node_name.toString

    def node_perspective: Document.Node.Perspective_Text =
      Document.Node.Perspective(true, Text.Perspective.full, Document.Node.Overlays.empty)

    def node_edits(old: Option[Theory]): List[Document.Edit_Text] =
    {
      val text_edits =
        if (old.isEmpty) Text.Edit.inserts(0, text)
        else Text.Edit.replace(0, old.get.text, text)

      if (text_edits.isEmpty) Nil
      else
        List(node_name -> Document.Node.Deps(node_header),
          node_name -> Document.Node.Edits(text_edits),
          node_name -> node_perspective)
    }
  }
}

class Thy_Resources(session_base: Sessions.Base, log: Logger = No_Logger)
  extends Resources(session_base, log = log)
{
  resources =>

  private val state = Synchronized(Thy_Resources.State())

  def read_thy(node_name: Document.Node.Name): Thy_Resources.Theory =
  {
    val path = node_name.path
    if (!node_name.is_theory) error("Not a theory file: " + path)

    val text = File.read(path)
    val node_header = resources.check_thy_reader(node_name, Scan.char_reader(text))
    new Thy_Resources.Theory(node_name, node_header, text)
  }

  def load_theories(
    session: Session,
    theories: List[(String, Position.T)],
    qualifier: String = Sessions.DRAFT,
    master_dir: String = "",
    progress: Progress = No_Progress): List[Document.Node.Name] =
  {
    val import_names =
      for ((thy, pos) <- theories)
      yield (import_name(qualifier, master_dir, thy), pos)

    val dependencies = resources.dependencies(import_names, progress = progress).check_errors
    val loaded_theories = dependencies.theories.map(read_thy(_))

    val edits =
      state.change_result(st =>
      {
        val theory_edits =
          for {
            theory <- loaded_theories
            node_name = theory.node_name
            edits = theory.node_edits(st.theories.get(node_name))
            if edits.nonEmpty
          } yield ((node_name, theory), edits)

        val st1 = st.copy(theories = st.theories ++ theory_edits.map(_._1))
        (theory_edits.flatMap(_._2), st1)
      })
    session.update(Document.Blobs.empty, edits)

    dependencies.theories
  }
}
