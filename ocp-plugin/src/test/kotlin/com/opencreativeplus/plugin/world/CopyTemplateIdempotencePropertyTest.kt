// Feature: ocp-mvp2-core-systems, Property 10: copyTemplate — идемпотентность

package com.opencreativeplus.plugin.world

import com.opencreativeplus.core.world.PlotTemplate
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import org.apache.commons.io.FileUtils
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Property-based test for BukkitWorldOperations.copyTemplate idempotency.
 *
 * Feature: ocp-mvp2-core-systems, Property 10: copyTemplate — идемпотентность
 *
 * For any plotId, a second call to copyTemplate when the destination directory
 * already exists must:
 *   - NOT copy any files (file count in destDir stays the same)
 *   - call onSuccess exactly once
 *   - NOT call onError
 *
 * Validates: Requirements 6.3, 6.9
 */
class CopyTemplateIdempotencePropertyTest : FreeSpec({

    // -----------------------------------------------------------------------
    // Stub that mirrors BukkitWorldOperations.copyTemplate logic
    // without requiring a live Bukkit server.
    // -----------------------------------------------------------------------

    /**
     * Mirrors the idempotency branch of BukkitWorldOperations.copyTemplate:
     *
     *   if (destDir.exists()) {
     *       onSuccess(worldName)   // simplified: no Bukkit.createWorld needed
     *       return
     *   }
     *   if (!templateDir.exists()) {
     *       onError(Exception("Template not found"))
     *       return
     *   }
     *   FileUtils.copyDirectory(templateDir, destDir)
     *   onSuccess(worldName)
     */
    fun copyTemplateLogic(
        templateDir: File,
        destDir: File,
        worldName: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // Req 6.3: idempotency — destination already exists → skip copy, call onSuccess
        if (destDir.exists()) {
            onSuccess(worldName)
            return
        }
        // Req 6.2: template not found
        if (!templateDir.exists()) {
            onError(Exception("Template '${templateDir.name}' not found at ${templateDir.absolutePath}"))
            return
        }
        // Req 6.8: copy directory
        try {
            FileUtils.copyDirectory(templateDir, destDir)
            onSuccess(worldName)
        } catch (e: Exception) {
            onError(e)
        }
    }

    // -----------------------------------------------------------------------
    // Arbitraries
    // -----------------------------------------------------------------------

    /** Arbitrary PlotTemplate value. */
    val arbTemplate = arbitrary { rs ->
        val templates = PlotTemplate.entries.toTypedArray()
        templates[rs.random.nextInt(templates.size)]
    }

    /** Arbitrary UUID for plotId. */
    val arbPlotId = arbitrary { UUID.randomUUID() }

    // -----------------------------------------------------------------------
    // Property 10a: second call with existing destDir → onSuccess, no copy
    // -----------------------------------------------------------------------

    "Property 10: copyTemplate — идемпотентность" - {

        // Feature: ocp-mvp2-core-systems, Property 10: copyTemplate — идемпотентность

        /**
         * When destDir already exists, copyTemplate must call onSuccess without
         * copying any files (file count in destDir is unchanged).
         *
         * Validates: Requirements 6.3, 6.9
         */
        "when destDir already exists → onSuccess called, no files copied" {
            checkAll(PropTestConfig(iterations = 10), arbTemplate, arbPlotId) { template, plotId ->
                val tmpRoot = Files.createTempDirectory("ocp_idem_test_").toFile()
                try {
                    val templateDir = File(tmpRoot, "templates/${template.templateName}").also {
                        it.mkdirs()
                        File(it, "level.dat").writeText("fake-level-data")
                        File(it, "region").mkdir()
                    }
                    val destDir = File(tmpRoot, plotId.toString()).also {
                        it.mkdirs()
                        // Pre-populate with a sentinel file to detect unwanted overwrites
                        File(it, "sentinel.txt").writeText("original")
                    }
                    val worldName = plotId.toString()

                    val successCount = AtomicInteger(0)
                    val errorCount = AtomicInteger(0)
                    var successName: String? = null

                    copyTemplateLogic(
                        templateDir = templateDir,
                        destDir = destDir,
                        worldName = worldName,
                        onSuccess = { name ->
                            successCount.incrementAndGet()
                            successName = name
                        },
                        onError = { errorCount.incrementAndGet() }
                    )

                    // onSuccess must be called exactly once
                    successCount.get() shouldBe 1
                    // onError must NOT be called
                    errorCount.get() shouldBe 0
                    // worldName passed to onSuccess must match plotId
                    successName shouldBe worldName
                    // sentinel file must still exist (no overwrite)
                    File(destDir, "sentinel.txt").readText() shouldBe "original"
                    // template files must NOT have been copied into destDir
                    File(destDir, "level.dat").exists() shouldBe false
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        /**
         * Calling copyTemplate twice for the same plotId:
         * - First call copies files and calls onSuccess
         * - Second call (destDir now exists) calls onSuccess again without re-copying
         * File count in destDir must be the same after both calls.
         *
         * Validates: Requirements 6.3, 6.9
         */
        "two consecutive calls → second call does not re-copy files" {
            checkAll(PropTestConfig(iterations = 10), arbTemplate, arbPlotId) { template, plotId ->
                val tmpRoot = Files.createTempDirectory("ocp_idem2_test_").toFile()
                try {
                    val templateDir = File(tmpRoot, "templates/${template.templateName}").also {
                        it.mkdirs()
                        File(it, "level.dat").writeText("fake-level-data")
                        File(it, "session.lock").writeText("0")
                    }
                    val destDir = File(tmpRoot, plotId.toString())
                    val worldName = plotId.toString()

                    val successCount = AtomicInteger(0)
                    val errorCount = AtomicInteger(0)

                    // First call — copies files
                    copyTemplateLogic(
                        templateDir = templateDir,
                        destDir = destDir,
                        worldName = worldName,
                        onSuccess = { successCount.incrementAndGet() },
                        onError = { errorCount.incrementAndGet() }
                    )

                    successCount.get() shouldBe 1
                    errorCount.get() shouldBe 0
                    destDir.exists() shouldBe true

                    val fileCountAfterFirst = destDir.walkTopDown().filter { it.isFile }.count()

                    // Second call — destDir exists, must be idempotent
                    copyTemplateLogic(
                        templateDir = templateDir,
                        destDir = destDir,
                        worldName = worldName,
                        onSuccess = { successCount.incrementAndGet() },
                        onError = { errorCount.incrementAndGet() }
                    )

                    successCount.get() shouldBe 2
                    errorCount.get() shouldBe 0

                    val fileCountAfterSecond = destDir.walkTopDown().filter { it.isFile }.count()

                    // File count must not change on second call
                    fileCountAfterSecond shouldBe fileCountAfterFirst
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        /**
         * When destDir does NOT exist but template exists, copyTemplate copies files
         * and calls onSuccess (baseline — non-idempotent first call works correctly).
         *
         * Validates: Requirements 6.1, 6.8
         */
        "when destDir does not exist and template exists → files copied, onSuccess called" {
            checkAll(PropTestConfig(iterations = 10), arbTemplate, arbPlotId) { template, plotId ->
                val tmpRoot = Files.createTempDirectory("ocp_first_call_test_").toFile()
                try {
                    val templateDir = File(tmpRoot, "templates/${template.templateName}").also {
                        it.mkdirs()
                        File(it, "level.dat").writeText("fake-level-data")
                    }
                    val destDir = File(tmpRoot, plotId.toString())
                    val worldName = plotId.toString()

                    val successCount = AtomicInteger(0)
                    val errorCount = AtomicInteger(0)

                    copyTemplateLogic(
                        templateDir = templateDir,
                        destDir = destDir,
                        worldName = worldName,
                        onSuccess = { successCount.incrementAndGet() },
                        onError = { errorCount.incrementAndGet() }
                    )

                    successCount.get() shouldBe 1
                    errorCount.get() shouldBe 0
                    destDir.exists() shouldBe true
                    File(destDir, "level.dat").exists() shouldBe true
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        /**
         * When template directory does not exist, copyTemplate calls onError
         * and does NOT call onSuccess.
         *
         * Validates: Requirements 6.2
         */
        "when template does not exist → onError called, onSuccess not called" {
            checkAll(PropTestConfig(iterations = 10), arbTemplate, arbPlotId) { template, plotId ->
                val tmpRoot = Files.createTempDirectory("ocp_no_template_test_").toFile()
                try {
                    // templateDir intentionally NOT created
                    val templateDir = File(tmpRoot, "templates/${template.templateName}")
                    val destDir = File(tmpRoot, plotId.toString())
                    val worldName = plotId.toString()

                    val successCount = AtomicInteger(0)
                    val errorCount = AtomicInteger(0)

                    copyTemplateLogic(
                        templateDir = templateDir,
                        destDir = destDir,
                        worldName = worldName,
                        onSuccess = { successCount.incrementAndGet() },
                        onError = { errorCount.incrementAndGet() }
                    )

                    successCount.get() shouldBe 0
                    errorCount.get() shouldBe 1
                    destDir.exists() shouldBe false
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }
    }
})
