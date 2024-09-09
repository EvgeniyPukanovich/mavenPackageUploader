import cats.effect.std.{Console, Mutex}
import cats.effect.syntax.all.*
import cats.effect.{Async, Temporal}
import cats.implicits.*
import fs2.io.file.{Files, Flags, Path}

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.sys.process.*

class MavenUploader[F[_]: Files: Async: Temporal: Console] private (
    gitlabUrl: String,
    repositoryId: String,
    baseDir: Path,
    deployedPoms: Path,
    notDeployedPoms: Path,
    maxConcurrent: Int = 10
) {

  private def deployArtifact(
      jarFile: String,
      pomFile: String,
      sourcesFile: Option[String] = None,
      javadocFile: Option[String] = None
  ): F[Either[Throwable, String]] = {
    val cmdArguments = Seq(
      "mvn deploy:deploy-file",
      s"-Dfile=$jarFile",
      s"-DpomFile=$pomFile",
      s"-DrepositoryId=$repositoryId",
      s"-Durl=$gitlabUrl",
      sourcesFile.map(sourceFile => s"-Dsources=$sourceFile").getOrElse(""),
      javadocFile.map(javadocFile => s"-Djavadoc=$javadocFile").getOrElse("")
    )
    val resultCommand = cmdArguments.mkString(" ").trim

    Async[F].interruptible(resultCommand.!!).timeout(5 minutes).attempt
  }

  private def writeToFile(path: Path, string: String, mutex: Mutex[F]) = {
    mutex.lock.surround {
      fs2.Stream(string).through(Files[F].writeUtf8Lines(path, Flags.Append)).compile.drain
    }
  }

  private def deployWithAdditionalJars(jar: Path, pom: Path) = {
    val source = jar.toString.replace(".jar", "-sources.jar")
    val javadoc = jar.toString.replace(".jar", "-javadoc.jar")
    (Files[F].exists(Path(source)), Files[F].exists(Path(javadoc)))
      .flatMapN { case (sourceExists, javadocExists) =>
        deployArtifact(
          jar.toString,
          pom.toString,
          if (sourceExists) Some(source) else None,
          if (javadocExists) Some(javadoc) else None
        )
      }
  }

  private def uploadAllArtifacts(
      alreadyDeployed: Set[String],
      mutexDeployed: Mutex[F],
      mutexNotDeployed: Mutex[F]
  ): F[(Int, Int)] =
    Files[F]
      .walk(baseDir)
      .evalFilterAsync(maxConcurrent) { path =>
        Files[F].isRegularFile(path).map { isRegFile =>
          val stringPth = path.toString
          isRegFile && stringPth.endsWith(".jar") && !stringPth.endsWith("-sources.jar") && !stringPth.endsWith(
            "-javadoc.jar"
          )
        }
      }
      .parEvalMap(maxConcurrent) { path =>
        val pomFile = path.toString.replace(".jar", ".pom")
        Files[F]
          .exists(Path(pomFile))
          .ifM(
            Async[F].pure(Some((path, Path(pomFile)))),
            Console[F].println("NO POM FILE IN " + pomFile).map(_ => None)
          )
      }
      .unNone
      .filterNot(tup => alreadyDeployed.contains(tup._2.toString))
      .parEvalMap(maxConcurrent) { case (jar, pom) =>
        deployWithAdditionalJars(jar, pom)
          .flatTap {
            case Left(value)  => writeToFile(notDeployedPoms, s"$pom \nError: ${value.getMessage}", mutexNotDeployed)
            case Right(value) => writeToFile(deployedPoms, pom.toString, mutexDeployed)
          }
      }
      .foldMap {
        // Get number of successful and unsuccessful uploads
        case Left(_)  => (0, 1)
        case Right(_) => (1, 0)
      }
      .compile
      .lastOrError

  /** @return
    *   (successful, unsuccessful) uploads
    */
  def uploadAllArtifacts: F[(Int, Int)] =
    for
      alreadyDeployed <- Files[F]
        .exists(deployedPoms)
        .ifM(
          Files[F].readUtf8Lines(deployedPoms).compile.toList.map(_.toSet),
          Files[F].createFile(deployedPoms) >> Async[F].pure(Set.empty[String])
        )
      mutexDeployed <- Mutex[F]
      mutexNotDeployed <- Mutex[F]
      results <- uploadAllArtifacts(alreadyDeployed, mutexDeployed, mutexNotDeployed)
    yield results
}

object MavenUploader {

  def apply[F[_]: Files: Async: Temporal: Console](
      gitlabUrl: String,
      repositoryId: String,
      baseDir: Path,
      deployedPoms: Path,
      notDeployedPoms: Path
  ): MavenUploader[F] =
    new MavenUploader(gitlabUrl, repositoryId, baseDir, deployedPoms, notDeployedPoms)
}
