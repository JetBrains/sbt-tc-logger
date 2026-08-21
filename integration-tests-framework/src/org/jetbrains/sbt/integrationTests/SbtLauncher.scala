package org.jetbrains.sbt.integrationTests

import java.io.{File, IOException, InputStream}
import java.net.{HttpURLConnection, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}

/**
 * Downloads and caches the sbt launcher jar used by nested integration-test sbt processes.
 *
 * The launcher version is selected by the test runtime, keeping the nested process isolated from the harness build.
 */
object SbtLauncher {
  private val MavenCentralBaseUrl = "https://repo1.maven.org/maven2"
  private val SbtIvyReleasesBaseUrl = "https://repo.scala-sbt.org/scalasbt/ivy-releases"
  private val LauncherArtifactPath = "org/scala-sbt/sbt-launch"
  private val LauncherJarName = "sbt-launch.jar"
  private val MaxRedirects = 10

  private lazy val launcherCache = new java.util.concurrent.ConcurrentHashMap[String, File]()

  def sbtLauncher(root: File, version: String): File =
    launcherCache.computeIfAbsent(s"${root.getAbsolutePath}:$version", _ => cachedLauncher(root, version))

  private def cachedLauncher(root: File, version: String): File = {
    val launcher = new File(new File(SbtIntegrationTestLayout.sbtLauncherCache(root), version), LauncherJarName).getAbsoluteFile
    val isDownloaded = launcher.isFile && (launcher.length() != 0L)
    if (!isDownloaded) {
      downloadLauncher(version, launcher)
    }
    launcher
  }

  private def downloadLauncher(version: String, launcher: File): Unit = {
    val urls = launcherUrls(version)
    try {
      Files.createDirectories(launcher.getParentFile.toPath)
      downloadFromFirstAvailable(urls, launcher)
    }
    catch {
      case cause: Exception =>
        val message = s"Could not download org.scala-sbt:sbt-launch:$version from ${urls.mkString(", ")} to ${launcher.getAbsolutePath}"
        throw new IllegalStateException(message, cause)
    }
  }

  private def downloadFromFirstAvailable(urls: Seq[String], launcher: File): Unit = {
    var lastFailure: Option[Exception] = None
    var downloaded = false

    val iterator = urls.iterator
    while (!downloaded && iterator.hasNext) {
      val url = iterator.next()
      val temp = Files.createTempFile(launcher.getParentFile.toPath, "sbt-launch-", ".jar")
      try {
        try {
          downloadWithJdk(url, temp)
        } catch {
          case jdkFailure: Exception =>
            try downloadWithCurl(url, temp)
            catch {
              case curlFailure: Exception =>
                jdkFailure.addSuppressed(curlFailure)
                throw jdkFailure
            }
        }

        if (Files.size(temp) == 0L) {
          throw new IOException(s"Downloaded launcher jar from $url is empty")
        }

        Files.move(temp, launcher.toPath, StandardCopyOption.REPLACE_EXISTING)
        downloaded = true
      } catch {
        case cause: Exception =>
          lastFailure = Some(cause)
      } finally {
        Files.deleteIfExists(temp)
      }
    }

    if (!downloaded) {
      throw lastFailure.getOrElse(new IOException("No launcher URLs were provided"))
    }
  }

  private def downloadWithJdk(url: String, temp: java.nio.file.Path): Unit = {
    val input = openArtifactStream(url)
    try Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING)
    finally input.close()
  }

  private def downloadWithCurl(url: String, temp: java.nio.file.Path): Unit = {
    val process = new ProcessBuilder("curl", "-fsSL", "--noproxy", "*", url, "-o", temp.toAbsolutePath.toString)
      .redirectErrorStream(true)
      .start()
    val output = String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      throw new IOException(s"curl exited with code $exitCode while downloading $url: $output")
    }
  }

  private def openArtifactStream(url: String): InputStream = {
    var currentUri = URI.create(url)
    var redirects = 0

    while (redirects <= MaxRedirects) {
      val connection = currentUri.toURL.openConnection()
      connection.setRequestProperty("Accept", "*/*")
      connection.setRequestProperty("User-Agent", "curl/8.7.1")

      connection match {
        case http: HttpURLConnection =>
          http.setInstanceFollowRedirects(false)
          val status = http.getResponseCode
          if (isRedirect(status)) {
            val location = http.getHeaderField("Location")
            http.disconnect()
            if (location == null || location.trim.isEmpty) {
              throw new IOException(s"HTTP $status from $currentUri did not include a Location header")
            }
            currentUri = currentUri.resolve(location)
            redirects += 1
          }
          else if (status >= 400) {
            throw new IOException(s"HTTP $status ${http.getResponseMessage} from $currentUri")
          }
          else {
            return http.getInputStream
          }
        case _ =>
          return connection.getInputStream
      }
    }

    throw new IOException(s"Too many redirects while downloading $url")
  }

  private def isRedirect(status: Int): Boolean =
    status == HttpURLConnection.HTTP_MOVED_PERM ||
      status == HttpURLConnection.HTTP_MOVED_TEMP ||
      status == HttpURLConnection.HTTP_SEE_OTHER ||
      status == 307 ||
      status == 308

  private def launcherUrls(version: String): Seq[String] = Seq(
    s"$MavenCentralBaseUrl/$LauncherArtifactPath/$version/sbt-launch-$version.jar",
    s"$SbtIvyReleasesBaseUrl/$LauncherArtifactPath/$version/jars/sbt-launch-$version.jar",
    s"$SbtIvyReleasesBaseUrl/$LauncherArtifactPath/$version/jars/sbt-launch.jar"
  )
}
