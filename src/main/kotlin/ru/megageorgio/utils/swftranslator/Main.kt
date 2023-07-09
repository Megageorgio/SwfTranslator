@file:OptIn(ExperimentalSerializationApi::class, ExperimentalCli::class)

package ru.megageorgio.utils.swftranslator

import com.deepl.api.Translator
import com.jpexs.decompiler.flash.SWF
import com.jpexs.decompiler.flash.tags.DefineFont3Tag
import com.jpexs.decompiler.flash.tags.base.MissingCharacterHandler
import com.jpexs.decompiler.flash.tags.base.TextTag
import kotlinx.cli.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.awt.Font
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

val heightRegex = Regex("""^height (\d+)$""", RegexOption.MULTILINE)
val letterSpacingRegex = Regex("""^letterspacing (-?\d+)$""", RegexOption.MULTILINE)
val colorRegex = Regex("""^color (.+)$""", RegexOption.MULTILINE)

fun main(args: Array<String>) {
    val parser = ArgParser("SwfTranslator")

    class Parse : Subcommand("parse", "Parse text from swf") {
        val input by argument(ArgType.String, description = "Input file (swf)")
        val output by option(ArgType.String, shortName = "o", description = "Output file (json)")

        override fun execute() {
            val outputPath = output ?: input.replace(".swf", ".json")
            val tags = parse(input)
            saveToJson(tags, outputPath)
            println("Saved to $outputPath")
        }
    }

    class Translate : Subcommand("translate", "Translate swf or json using DeepL") {
        val input by argument(ArgType.String, description = "Input file (swf or json)")
        val authKey by option(
            ArgType.String, shortName = "k", description = "DeepL auth key (or set env var DEEPL_KEY)"
        )
        val sourceLang by option(ArgType.String, shortName = "s", description = "Source language (en, ru, etc.)")
        val targetLang by option(
            ArgType.String, shortName = "t", description = "Target language (en, ru, etc.)"
        ).default("en-US")
        val fontName by option(
            ArgType.String, shortName = "f", description = "Font to use by default if original is not supported"
        ).default("Arial")

        val output by option(ArgType.String, shortName = "o", description = "Output file (swf or json)")

        override fun execute() {
            val key = authKey ?: System.getenv("DEEPL_KEY")
            if (key == null) {
                println("Auth key is required")
            }
            val extension = Path(input).extension
            val fileName = Path(input).nameWithoutExtension
            val outputPath = output ?: input.replace(fileName, "$fileName-translated")
            when (extension) {
                "json" -> {
                    val tags = loadFromJson(input)
                    translate(tags, key, sourceLang, targetLang)
                    saveToJson(tags, outputPath)
                }
                "swf" -> {
                    val tags = parse(input)
                    translate(tags, key, sourceLang, targetLang)
                    patch(input, tags, fontName, outputPath)
                }
                else -> {
                    println("This extension is not supported")
                    return;
                }
            }

            println("Saved to $outputPath")
        }
    }

    class Patch : Subcommand("patch", "Patch swf") {
        val swfInput by argument(ArgType.String, description = "Input file (swf)")
        val jsonInput by argument(ArgType.String, description = "Input file (json")
        val output by option(ArgType.String, shortName = "o", description = "Output file (swf)")
        val fontName by option(
            ArgType.String, shortName = "f", description = "Font to use if original is not supported"
        ).default("Arial")

        override fun execute() {
            val fileName = Path(swfInput).nameWithoutExtension
            val outputPath = output ?: swfInput.replace(fileName, "$fileName-translated")
            val tags = loadFromJson(jsonInput)
            patch(swfInput, tags, fontName, outputPath)
            println("Saved to $outputPath")
        }
    }

    val parse = Parse()
    val translate = Translate()
    val patch = Patch()
    parser.subcommands(parse, translate, patch)
    try {
        parser.parse(if (args.isEmpty()) arrayOf("-h") else args)
    } catch (ex: Exception) {
        ex.printStackTrace()
    }
}

fun parse(input: String): List<TagRecord> {
    val tags: MutableList<TagRecord> = mutableListOf()

    FileInputStream(input).use { fis ->
        val swf = SWF(fis, true)

        for (t in swf.tags) {
            if (t is TextTag && t.texts.isNotEmpty() && t.texts.any { it.isNotEmpty() }) {
                val formattedText = t.getFormattedText(false).text
                val heightList = heightRegex.findAll(formattedText).map { it.groupValues[1] }.toList()
                val letterSpacingList = letterSpacingRegex.findAll(formattedText).map { it.groupValues[1] }.toList()
                val colorList = colorRegex.findAll(formattedText).map { it.groupValues[1] }.toList()

                tags.add(
                    TagRecord(t.characterId, t.texts.mapIndexed { index, text ->
                        RowRecord(
                            originalText = text,
                            height = heightList.getOrNull(index),
                            letterSpacing = letterSpacingList.getOrNull(index),
                            color = colorList.getOrNull(index)
                        )
                    })
                )
            }
        }
    }

    return tags
}

fun translate(tags: List<TagRecord>, authKey: String, sourceLang: String?, targetLang: String) {
    val translator = Translator(authKey)
    for ((translatedTagsCount, tag) in tags.withIndex()) {
        if (translatedTagsCount % 10 == 0) {
            println("Progress: $translatedTagsCount/${tags.size}")
        }

        for (row in tag.rows) {
            if (row.translatedText.isEmpty() && row.originalText.trim().isNotEmpty()) {
                row.translatedText = translator.translateText(row.originalText, sourceLang, targetLang).text
            }
        }
    }
    println("Finished")
}

fun patch(input: String, tags: List<TagRecord>, fontName: String, output: String, addFont: Boolean = false) {
    var universalFontId = 0

    FileInputStream(input).use { fis ->
        val swf = SWF(fis, true)
        if (addFont) {
            val font = Font(fontName, Font.PLAIN, 18)
            val fontTag = DefineFont3Tag(swf)
            val allChars =
                tags.flatMap { it.rows.flatMap { it.originalText.asIterable() + it.translatedText.asIterable() } }
                    .distinct()
            for (char in allChars) {
                fontTag.addCharacter(char, font)
            }
            swf.addTag(0, fontTag)
            universalFontId = fontTag.characterId
        }

        for (t in swf.tags) {
            if (t is TextTag && t.texts.isNotEmpty()) {
                var formattedText = t.getFormattedText(false).text;
                val newTag = tags.find { it.id == t.characterId }
                if (newTag !== null) {
                    for (row in newTag.rows) {
                        formattedText = heightRegex.replaceFirst(formattedText, "height[RMV] ${row.height}")
                        formattedText =
                            letterSpacingRegex.replaceFirst(formattedText, "letterspacing[RMV] ${row.letterSpacing}")
                        formattedText = colorRegex.replaceFirst(formattedText, "color[RMV] ${row.color}")
                    }
                    formattedText = formattedText.replace("[RMV]", "")
                    println(formattedText)
                    val isSuccess = t.setFormattedText(MissingCharacterHandler(),
                        formattedText,
                        newTag.rows.map { if (it.translatedText.isEmpty()) it.originalText else it.translatedText }
                            .toTypedArray())
                    if (!isSuccess) {
                        if (!addFont) {
                            universalFontId = -1
                            break
                        }

                        for (fontId in t.fontIds) {
                            formattedText = formattedText.replace("font $fontId", "font $universalFontId")
                        }

                        t.setFormattedText(MissingCharacterHandler(),
                            formattedText,
                            newTag.rows.map { if (it.translatedText.isEmpty()) it.originalText else it.translatedText }
                                .toTypedArray())
                    }
                    t.setModified(true)
                }
            }
        }

        if (universalFontId == -1) {
            fis.close()
            patch(input, tags, fontName, output, true)
            return
        }

        FileOutputStream(output).use { fos ->
            swf.saveTo(fos)
        }
    }
}

fun saveToJson(tags: List<TagRecord>, output: String) {
    val json = Json { prettyPrint = true }
    File(output).writeText(json.encodeToString(tags))
}

fun loadFromJson(input: String): List<TagRecord> {
    return Json.decodeFromString(File(input).readText())
}

@Serializable
data class TagRecord(val id: Int, val rows: List<RowRecord>);

@Serializable
data class RowRecord(
    val originalText: String,
    @EncodeDefault var translatedText: String = "",
    val height: String?,
    val letterSpacing: String?,
    val color: String?
)
