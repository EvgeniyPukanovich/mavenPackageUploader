import cats.effect.{ExitCode, IO, IOApp}
import fs2.io.file.Path

object Main extends IOApp {
  
  override def run(args: List[String]): IO[ExitCode] = {
    args match
      case registryUrl :: repositoryId :: baseDir :: deployedPoms :: notDeployedPoms :: Nil =>
        val mavenUploader =
          MavenUploader[IO](registryUrl, repositoryId, Path(baseDir), Path(deployedPoms), Path(notDeployedPoms))
        mavenUploader.uploadAllArtifacts.attempt.flatMap {
          case Left(value) => IO.println(value.getMessage).map(_ => ExitCode.Error)
          case Right(value) =>
            IO.println(s"Successful: ${value._1} Unsuccessful: ${value._2}").map(_ => ExitCode.Success)
        }
      case _ => IO.println("Wrong arguments").as(ExitCode.Error)
  }
}
