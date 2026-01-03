package io.github.woneum.libfetch.plugin

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.component3

class LibFetchPlugin : JavaPlugin() {

    private val serverLibs: File
        get() = File("libraries") // PaperMC server 루트 기준

    override fun onEnable() {
        Bukkit.getLogger().info("[LibFetchPlugin] Plugin enabled!")
        saveDefaultConfig()
        loadLibraries()

        Bukkit.getScheduler().runTaskTimer(this, Updater(File(dataFolder, "config.yml")), 0L, 1L)
    }

    fun loadLibraries() {
        val libraries = config.getStringList("libraries")
        for (lib in libraries) {
            val parts = lib.split(":")
            if (parts.size != 3) continue
            val group = parts[0]
            val artifact = parts[1]
            val version = parts[2]

            val jarFile = getLibraryFile(group, artifact, version)
            val pomFile = getPomFile(group, artifact, version)

            if (!jarFile.exists()) {
                try {
                    if (downloadLibraryFromMavenLocal(group, artifact, version, jarFile, pomFile)) {
                        Bukkit.getLogger().info("Loaded $artifact-$version from Maven Local")
                    } else if (downloadLibraryFromJitPack(group, artifact, version, jarFile, pomFile)) {
                        Bukkit.getLogger().info("Loaded $artifact-$version from JitPack")
                    } else {
                        Bukkit.getLogger().warning("Could not find $artifact-$version in Maven Local or JitPack!")
                        continue
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    continue
                }
            }

            // 동적 클래스 로딩
            val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()), this.javaClass.classLoader)
        }

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.size != 3) return false
        val (group, artifact, version) = args
        val jarFile = getLibraryFile(group, artifact, version)
        val pomFile = getPomFile(group, artifact, version)

        try {
            if (!jarFile.exists()) {
                if (downloadLibraryFromMavenLocal(group, artifact, version, jarFile, pomFile)) {
                    sender.sendMessage("Loaded $artifact-$version from Maven Local")
                } else if (downloadLibraryFromJitPack(group, artifact, version, jarFile, pomFile)) {
                    sender.sendMessage("Loaded $artifact-$version from JitPack")
                } else {
                    sender.sendMessage("Could not find $artifact-$version in Maven Local or JitPack!")
                    return true
                }
            }

            // 동적 클래스 로딩
            val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()), this.javaClass.classLoader)
            sender.sendMessage("Library $artifact-$version loaded!")
        } catch (e: IOException) {
            e.printStackTrace()
            sender.sendMessage("Failed to load library $artifact-$version")
        }

        return true
    }

    /** Maven Local (~/.m2/repository) */
    private fun getWindowsM2Path(): File? {
        return if (System.getProperty("os.name").lowercase().contains("linux")) {
            // WSL 환경이면 C 드라이브를 /mnt/c/... 로 변환
            val userDir = File("/mnt/c/Users")
            if (!userDir.exists() || !userDir.isDirectory) return null
            userDir.listFiles()
                .filter { it.isDirectory }
                .map { File(it, ".m2/repository") }
                .firstOrNull { it.exists() && it.isDirectory }
        } else {
            // Windows라면 그냥 C: 드라이브
            File(System.getProperty("user.home"), ".m2\\repository")
        }
    }

    @Throws(IOException::class)
    private fun downloadLibraryFromMavenLocal(group: String, artifact: String, version: String, jarOutput: File, pomOutput: File): Boolean {
        val possibleRepos = listOf(
            // 1. Linux 홈 (~/.m2/repository)
            Paths.get(System.getProperty("user.home"), ".m2", "repository").toFile(),
            // 2. Windows 경로 (WSL이면 /mnt/c/... 형식)
            getWindowsM2Path()
        )

        for (repo in possibleRepos) {
            val path = repo?.absolutePath ?: continue
            val basePath = Paths.get(
                path,
                *group.split(".").toTypedArray(),
                artifact,
                version
            )
            val localFile = basePath.resolve("$artifact-$version.jar").toFile()
            val localPom = basePath.resolve("$artifact-$version.pom").toFile()

            println("Checking Maven Local path: ${localFile.absolutePath}")

            if (localFile.exists()) {
                jarOutput.parentFile.mkdirs()
                Files.copy(localFile.toPath(), jarOutput.toPath(), StandardCopyOption.REPLACE_EXISTING)

                if (localPom.exists()) {
                    Files.copy(localPom.toPath(), pomOutput.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }

                println("Copied $artifact-$version.jar from Maven Local: ${localFile.absolutePath}")
                return true
            } else {
                println("File not found in this repository: ${localFile.absolutePath}")
            }
        }

        return false
    }

    /** JitPack */
    @Throws(IOException::class)
    private fun downloadLibraryFromJitPack(group: String, artifact: String, version: String, jarOutput: File, pomOutput: File): Boolean {
        val baseUrl = "https://jitpack.io/${group.replace('.', '/')}/$artifact/$version"
        try {
            jarOutput.parentFile.mkdirs()

            URI("$baseUrl/$artifact-$version.jar").toURL().openStream().use {
                Files.copy(it, jarOutput.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            try {
                URI("$baseUrl/$artifact-$version.pom").toURL().openStream().use {
                    Files.copy(it, pomOutput.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (_: IOException) {}

            return true
        } catch (_: IOException) {}

        return false
    }

    /** PaperMC server libraries 폴더 내 Maven 구조 경로 반환 */
    private fun getLibraryFile(group: String, artifact: String, version: String): File {
        val folder = File(serverLibs, "${group.replace('.', '/')}/$artifact/$version")
        if (!folder.exists()) folder.mkdirs()
        return File(folder, "$artifact-$version.jar")
    }

    private fun getPomFile(group: String, artifact: String, version: String): File {
        val folder = File(serverLibs, "${group.replace('.', '/')}/$artifact/$version")
        if (!folder.exists()) folder.mkdirs()
        return File(folder, "$artifact-$version.pom")
    }

    inner class Updater(private val file: File): Runnable {
        private var lastModified: Long = file.lastModified()

        override fun run() {
            if (lastModified != file.lastModified()) {
                val config = YamlConfiguration.loadConfiguration(file)
                Bukkit.getLogger().info("Updating Libraries")
                config.save(file)
                loadLibraries()
            }
        }
    }
}