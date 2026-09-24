package com.replaymod.gradle.remap

import com.replaymod.gradle.remap.legacy.LegacyMapping
import org.cadixdev.lorenz.MappingSet
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.ContentRoot
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.com.intellij.codeInsight.CustomExceptionHandler
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.PathUtil
import java.io.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.system.exitProcess

// fallen's fork: optimize use physical source roots for PSI
data class PhysicalSourceFile(
    val file: File,
    val sourceRoot: File,
    val sourceText: String,
)

class Transformer(private val map: MappingSet) {
    var classpath: Array<String>? = null
    var remappedClasspath: Array<String>? = null
    var jdkHome: File? = null
    var remappedJdkHome: File? = null
    var patternAnnotation: String? = null
    var manageImports = false
    var enableMessageCollector = true
    var verboseCompilerMessages = false

    @Throws(IOException::class)
    fun remap(sources: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> =
            remap(sources, emptyMap())

    @Throws(IOException::class)
    fun remap(sources: Map<String, String>, processedSources: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> {
        // fallen's fork: optimize use physical source roots for PSI - extract common impl
        return remapInternal(sources, processedSources, null)
    }

    // fallen's fork: optimize use physical source roots for PSI - add PhysicalSourceFile variant
    @Throws(IOException::class)
    fun remapFromFiles(sources: Map<String, PhysicalSourceFile>, processedSources: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> {
        return remapInternal(sources.mapValues { it.value.sourceText }, processedSources, sources)
    }

    // fallen's fork: optimize use physical source roots for PSI - extract common impl
    private fun remapInternal(sources: Map<String, String>, processedSources: Map<String, String>, physicalSourceFiles: Map<String, PhysicalSourceFile>?): Map<String, Pair<String, List<Pair<Int, String>>>> {
        val tmpDir = if (physicalSourceFiles == null) Files.createTempDirectory("remap") else null
        val processedTmpDir = if (manageImports) Files.createTempDirectory("remap-processed") else null  // fallen's fork: optimize skip unused processed temp root
        val disposable = Disposer.newDisposable()
        try {
            if (physicalSourceFiles == null) {  // fallen's fork: optimize use physical source roots for PSI - warp with if
                for ((unitName, source) in sources) {
                    val path = tmpDir!!.resolve(unitName)
                    Files.createDirectories(path.parent)
                    Files.write(path, source.toByteArray(StandardCharsets.UTF_8), StandardOpenOption.CREATE)

                    // fallen's fork: optimize skip unused processed temp root - begin
                    processedTmpDir?.let { processedRoot ->
                        val processedSource = processedSources[unitName] ?: source
                        val processedPath = processedRoot.resolve(unitName)
                        Files.createDirectories(processedPath.parent)
                        Files.write(processedPath, processedSource.toByteArray(), StandardOpenOption.CREATE)
                    }
                    // fallen's fork: optimize skip unused processed temp root - end
                }
            } else {
                // fallen's fork: optimize skip unused processed temp root - begin
                processedTmpDir?.let { processedRoot ->
                    for ((unitName, source) in sources) {
                        val processedSource = processedSources[unitName] ?: source
                        val processedPath = processedRoot.resolve(unitName)
                        Files.createDirectories(processedPath.parent)
                        Files.write(processedPath, processedSource.toByteArray(), StandardOpenOption.CREATE)
                    }
                }
                // fallen's fork: optimize skip unused processed temp root - end
            }

            val config = CompilerConfiguration()
            config.put(CommonConfigurationKeys.MODULE_NAME, "main")
            jdkHome?.let {config.setupJdk(it) }

            // fallen's fork: optimize use physical source roots for PSI - begin
            val sourceRoots = if (physicalSourceFiles == null) {
                listOf(tmpDir!!.toFile())
            } else {
                physicalSourceFiles.values.map { it.sourceRoot.absoluteFile }.distinctBy { it.toPath().normalize() }
            }
            sourceRoots.forEach { sourceRoot ->  // fallen's fork: optimize use physical source roots for PSI - warp with sourceRoots.forEach
                config.add<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, JavaSourceRoot(sourceRoot, ""))
                val kotlinSourceRoot = try {
                    kotlinSourceRoot1521(sourceRoot.absolutePath, false)
                } catch (e: NoSuchMethodError) {
                    kotlinSourceRoot190(sourceRoot.absolutePath, false)
                }
                config.add<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, kotlinSourceRoot)
            }
            // fallen's fork: optimize use physical source roots for PSI - end

            config.addAll<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, classpath!!.map { JvmClasspathRoot(File(it)) })
            config.put<MessageCollector>(
                CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
                if (enableMessageCollector) PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, verboseCompilerMessages)
                else MessageCollector.NONE
            )

            // Our PsiMapper only works with the PSI tree elements, not with the faster (but kotlin-specific) classes
            config.put(JVMConfigurationKeys.USE_PSI_CLASS_FILES_READING, true)

            // Mark Registry as loaded, otherwise RegistryKey will (provided a sufficiently complex project) log
            // messages about it being accessed before it is loaded (and it won't ever be loaded naturally).
            val loadedField = try {
                Registry::class.java.getDeclaredField("myLoaded")
            } catch (_: NoSuchFieldException) {
                Registry::class.java.getDeclaredField("isLoaded")
            }
            loadedField.isAccessible = true
            loadedField.set(Registry.getInstance(), true)

            val environment = KotlinCoreEnvironment.createForProduction(
                    disposable,
                    config,
                    EnvironmentConfigFiles.JVM_CONFIG_FILES
            )
            @Suppress("DEPRECATION")
            val rootArea = Extensions.getRootArea()
            synchronized(rootArea) {
                if (!rootArea.hasExtensionPoint(CustomExceptionHandler.KEY)) {
                    rootArea.registerExtensionPoint(CustomExceptionHandler.KEY.name, CustomExceptionHandler::class.java.name, ExtensionPoint.Kind.INTERFACE)
                }
            }

            val project = environment.project as MockProject
            val psiManager = PsiManager.getInstance(project)
            val vfs = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL) as CoreLocalFileSystem

            // fallen's fork: debug remap profiling - begin
            var sourceFileLookupCalls = 0
            var sourceFileLookupNanos = 0L
            var psiFileLookupCalls = 0
            var psiFileLookupNanos = 0L
            fun findSourceFile(name: String): VirtualFile {
                val start = System.nanoTime()
                val result = if (physicalSourceFiles == null) {
                    vfs.findFileByIoFile(tmpDir!!.resolve(name).toFile())!!
                } else {
                    vfs.findFileByIoFile(physicalSourceFiles.getValue(name).file)!!
                }
                sourceFileLookupCalls++
                sourceFileLookupNanos += System.nanoTime() - start
                return result
            }
            fun findPsiFile(file: VirtualFile): PsiFile {
                val start = System.nanoTime()
                val result = psiManager.findFile(file)!!
                psiFileLookupCalls++
                psiFileLookupNanos += System.nanoTime() - start
                return result
            }
            // fallen's fork: debug remap profiling - end

            // fallen's fork: optimize use physical source roots for PSI - begin
            val virtualFiles = sources.mapValues { findSourceFile(it.key) }
            // fallen's fork: optimize use physical source roots for PSI - end

            val psiFiles = virtualFiles.mapValues { findPsiFile(it.value) }
            val ktFiles = psiFiles.values.filterIsInstance<KtFile>()

            val analysis = try {
                analyze1521(environment, ktFiles)
            } catch (e: NoSuchMethodError) {
                try {
                    analyze1620(environment, ktFiles)
                } catch (e: NoSuchMethodError) {
                    analyze200(environment, ktFiles)
                }
            }

            val remappedEnv = remappedClasspath?.let {
                setupRemappedProject(disposable, it, processedTmpDir)
            }

            val patterns = patternAnnotation?.let { annotationFQN ->
                val patterns = PsiPatterns(annotationFQN)
                val annotationName = annotationFQN.substring(annotationFQN.lastIndexOf('.') + 1)
                for ((unitName, source) in sources) {
                    if (!source.contains(annotationName)) continue
                    try {
                        val patternFile = findSourceFile(unitName)
                        val patternPsiFile = findPsiFile(patternFile)
                        patterns.read(patternPsiFile, processedSources[unitName]!!)
                    } catch (e: Exception) {
                        throw RuntimeException("Failed to read patterns from file \"$unitName\".", e)
                    }
                }
                patterns
            }

            val autoImports = if (manageImports && remappedEnv != null) {
                AutoImports(remappedEnv)
            } else {
                null
            }

            val results = HashMap<String, Pair<String, List<Pair<Int, String>>>>()
            // fallen's fork: debug remap profiling - begin
            val psiMapperDebugStats = PsiMapperDebugStats(debugPotentialMappingNames(map))
            // fallen's fork: debug remap profiling - end
            for (name in sources.keys) {
                val file = findSourceFile(name)
                val psiFile = findPsiFile(file)

                var (text, errors) = try {
                    PsiMapper(map, remappedEnv?.project, psiFile, analysis.bindingContext, patterns, psiMapperDebugStats).remapFile()
                } catch (e: Exception) {
                    throw RuntimeException("Failed to map file \"$name\".", e)
                }

                if (autoImports != null && "/* remap: no-manage-imports */" !in text) {
                    val processedText = processedSources[name] ?: text
                    text = autoImports.apply(psiFile, text, processedText)
                }

                results[name] = text to errors
            }
            // fallen's fork: debug remap profiling - begin
            System.err.println(
                "[remap-debug] psiLookup: sources=${sources.size}, " +
                    "sourceCalls=$sourceFileLookupCalls, sourceTime=${sourceFileLookupNanos / 1_000_000}ms, " +
                    "psiCalls=$psiFileLookupCalls, psiTime=${psiFileLookupNanos / 1_000_000}ms"
            )
            System.err.println(psiMapperDebugStats.summary())
            // fallen's fork: debug remap profiling - end
            return results
        } finally {
            // fallen's fork: optimize use physical source roots for PSI - begin
            tmpDir?.let { root ->
                Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
            // fallen's fork: optimize use physical source roots for PSI - end

            // fallen's fork: optimize skip unused processed temp root - begin
            processedTmpDir?.let { processedRoot ->
                Files.walk(processedRoot).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
            // fallen's fork: optimize skip unused processed temp root - end
            Disposer.dispose(disposable)
        }
    }

    private fun CompilerConfiguration.setupJdk(jdkHome: File) {
        put(JVMConfigurationKeys.JDK_HOME, jdkHome)

        if (!CoreJrtFileSystem.isModularJdk(jdkHome)) {
            val roots = PathUtil.getJdkClassesRoots(jdkHome).map { JvmClasspathRoot(it, true) }
            addAll(CLIConfigurationKeys.CONTENT_ROOTS, 0, roots)
        }
    }

    private fun setupRemappedProject(disposable: Disposable, classpath: Array<String>, sourceRoot: Path?): KotlinCoreEnvironment { // fallen's fork: optimize skip unused processed temp root
        val config = CompilerConfiguration()
        (remappedJdkHome ?: jdkHome)?.let { config.setupJdk(it) }
        config.put(CommonConfigurationKeys.MODULE_NAME, "main")
        config.addAll(CLIConfigurationKeys.CONTENT_ROOTS, classpath.map { JvmClasspathRoot(File(it)) })
        // fallen's fork: optimize skip unused processed temp root - begin
        if (manageImports) {
            config.add(CLIConfigurationKeys.CONTENT_ROOTS, JavaSourceRoot(requireNotNull(sourceRoot).toFile(), ""))
        }
        // fallen's fork: optimize skip unused processed temp root - end
        config.put(
            CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
            if (enableMessageCollector) PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, verboseCompilerMessages)
            else MessageCollector.NONE
        )

        val environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            config,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
        try {
            analyze1521(environment, emptyList())
        } catch (e: NoSuchMethodError) {
            try {
                analyze1620(environment, emptyList())
            } catch (e: NoSuchMethodError) {
                analyze200(environment, emptyList())
            }
        }
        return environment
    }

    companion object {

        @Throws(IOException::class)
        @JvmStatic
        fun main(args: Array<String>) {
            val mappings: MappingSet = if (args[0].isEmpty()) {
                MappingSet.create()
            } else {
                LegacyMapping.readMappingSet(File(args[0]).toPath(), args[1] == "true")
            }
            val transformer = Transformer(mappings)

            val reader = BufferedReader(InputStreamReader(System.`in`))

            transformer.classpath = (1..Integer.parseInt(args[2])).map { reader.readLine() }.toTypedArray()

            val sources = mutableMapOf<String, String>()
            while (true) {
                val name = reader.readLine()
                if (name == null || name.isEmpty()) {
                    break
                }

                val lines = arrayOfNulls<String>(Integer.parseInt(reader.readLine()))
                for (i in lines.indices) {
                    lines[i] = reader.readLine()
                }
                val source = lines.joinToString("\n")

                sources[name] = source
            }

            val results = transformer.remap(sources)

            for (name in sources.keys) {
                println(name)
                val lines = results.getValue(name).first.split("\n").dropLastWhile { it.isEmpty() }.toTypedArray()
                println(lines.size)
                for (line in lines) {
                    println(line)
                }
            }

            if (results.any { it.value.second.isNotEmpty() }) {
                exitProcess(1)
            }
        }

        init {
            // Fix "WARN: Failed to initialize native filesystem for Windows" warnings
            setIdeaIoUseFallback()

            // Mute intellij platform logger for those "WARN: The registry key 'xxx' accessed, but not loaded yet" warnings
            org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger.setFactory {
                org.jetbrains.kotlin.utils.PrintingLogger(PrintStream(object : OutputStream() {
                    override fun write(b: Int) {}
                }))
            }
        }
    }

}
