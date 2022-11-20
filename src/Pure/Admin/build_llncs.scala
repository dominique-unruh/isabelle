/*  Title:      Pure/Admin/build_llncs.scala
    Author:     Makarius

Build Isabelle component for Springer LaTeX LNCS style.

See also:

  - https://ctan.org/pkg/llncs?lang=en
  - https://www.springer.com/gp/computer-science/lncs/conference-proceedings-guidelines
*/

package isabelle


object Build_LLNCS {
  /* build llncs component */

  val default_url = "https://mirrors.ctan.org/macros/latex/contrib/llncs.zip"

  def build_llncs(
    download_url: String = default_url,
    target_dir: Path = Path.current,
    progress: Progress = new Progress
  ): Unit = {
    Isabelle_System.require_command("unzip", test = "-h")

    Isabelle_System.with_tmp_file("download", ext = "zip") { download_file =>
      Isabelle_System.with_tmp_dir("download") { download_dir =>

        /* download */

        Isabelle_System.download_file(download_url, download_file, progress = progress)
        Isabelle_System.bash("unzip -x " + File.bash_path(download_file),
          cwd = download_dir.file).check

        val llncs_dir =
          File.read_dir(download_dir) match {
            case List(name) => download_dir + Path.explode(name)
            case bad =>
              error("Expected exactly one directory entry in " + download_file +
                bad.mkString("\n", "\n  ", ""))
          }

        val readme = Path.explode("README.md")
        File.change(llncs_dir + readme)(_.replace("&nbsp;", "\u00a0"))


        /* component */

        val version = {
          val Version = """^_.* v(.*)_$""".r
          split_lines(File.read(llncs_dir + readme))
            .collectFirst({ case Version(v) => v })
            .getOrElse(error("Failed to detect version in " + readme))
        }

        val component = "llncs-" + version
        val component_dir =
          Components.Directory.create(target_dir + Path.basic(component), progress = progress)

        Isabelle_System.rm_tree(component_dir.path)
        Isabelle_System.copy_dir(llncs_dir, component_dir.path)
        Isabelle_System.make_directory(component_dir.etc)


        /* settings */

        File.write(component_dir.settings,
          """# -*- shell-script -*- :mode=shellscript:

ISABELLE_LLNCS_HOME="$COMPONENT"
""")


        /* README */

        File.write(component_dir.README,
          """This is the Springer LaTeX LNCS style for authors from
""" + download_url + """


    Makarius
    """ + Date.Format.date(Date.now()) + "\n")
      }
    }
  }


  /* Isabelle tool wrapper */

  val isabelle_tool =
    Isabelle_Tool("build_llncs", "build component for Springer LaTeX LNCS style",
      Scala_Project.here,
      { args =>
        var target_dir = Path.current
        var download_url = default_url

        val getopts = Getopts("""
Usage: isabelle build_llncs [OPTIONS]

  Options are:
    -D DIR       target directory (default ".")
    -U URL       download URL (default: """" + default_url + """")

  Build component for Springer LaTeX LNCS style.
""",
          "D:" -> (arg => target_dir = Path.explode(arg)),
          "U:" -> (arg => download_url = arg))

        val more_args = getopts(args)
        if (more_args.nonEmpty) getopts.usage()

        val progress = new Console_Progress()

        build_llncs(download_url = download_url, target_dir = target_dir, progress = progress)
      })
}
