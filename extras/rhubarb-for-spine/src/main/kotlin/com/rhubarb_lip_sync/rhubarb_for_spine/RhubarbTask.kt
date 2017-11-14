package com.rhubarb_lip_sync.rhubarb_for_spine

import com.beust.klaxon.JsonObject
import com.beust.klaxon.array
import com.beust.klaxon.double
import com.beust.klaxon.string
import com.beust.klaxon.Parser as JsonParser
import org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.Callable

class RhubarbTask(
	val audioFilePath: Path,
	val dialog: String?,
	val extendedMouthShapes: Set<MouthShape>,
	val progress: Progress
) : Callable<List<MouthCue>> {

	override fun call(): List<MouthCue> {
		if (Thread.currentThread().isInterrupted) {
			throw InterruptedException()
		}
		if (!Files.exists(audioFilePath)) {
			throw IllegalArgumentException("File '$audioFilePath' does not exist.");
		}


		val dialogFile = if (dialog != null) TemporaryTextFile(dialog) else null
		dialogFile.use {
			val processBuilder = ProcessBuilder(createProcessBuilderArgs(dialogFile?.filePath))
			val process: Process = processBuilder.start()
			val stdout = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
			val stderr = BufferedReader(InputStreamReader(process.errorStream, StandardCharsets.UTF_8))
			try {
				while (true) {
					val line = stderr.interruptibleReadLine()

					val message = parseJsonObject(line)
					when (message.string("type")!!) {
						"progress" -> {
							progress.reportProgress(message.double("value")!!)}
						"success" -> {
							progress.reportProgress(1.0)
							val resultString = stdout.readText()
							return parseRhubarbResult(resultString)
						}
						"failure" -> {
							throw Exception(message.string("reason"))
						}
					}
				}
			} catch (e: InterruptedException) {
				process.destroyForcibly()
				throw e
			} catch (e: EOFException) {
				throw Exception("Rhubarb terminated unexpectedly.")
			}
		}

		throw Exception("An unexpected error occurred.")
	}

	private fun parseRhubarbResult(jsonString: String): List<MouthCue> {
		val json = parseJsonObject(jsonString)
		val mouthCues = json.array<JsonObject>("mouthCues")!!
		return mouthCues.map { mouthCue ->
			val time = mouthCue.double("start")!!
			val mouthShape = MouthShape.valueOf(mouthCue.string("value")!!)
			return@map MouthCue(time, mouthShape)
		}
	}

	private val jsonParser = JsonParser()
	private fun parseJsonObject(jsonString: String): JsonObject {
		return jsonParser.parse(StringReader(jsonString)) as JsonObject
	}

	private fun createProcessBuilderArgs(dialogFilePath: Path?): List<String> {
		val extendedMouthShapesString =
			if (extendedMouthShapes.any()) extendedMouthShapes.joinToString(separator = "")
			else "\"\""
		return mutableListOf(
			rhubarbBinFilePath.toString(),
			"--machineReadable",
			"--exportFormat", "json",
			"--extendedShapes", extendedMouthShapesString
		).apply {
			if (dialogFilePath != null) {
				addAll(listOf(
					"--dialogFile", dialogFilePath.toString()
				))
			}
		}.apply {
			add(audioFilePath.toString())
		}

	}

	private val guiBinDirectory: Path by lazy {
		var path: String = ClassLoader.getSystemClassLoader().getResource(".")!!.path
		if (path.length >= 3 && path[2] == ':') {
			// Workaround for https://stackoverflow.com/questions/9834776/java-nio-file-path-issue
			path = path.substring(1)
		}
		return@lazy Paths.get(path)
	}

	private val rhubarbBinFilePath: Path by lazy {
		val rhubarbBinName = if (IS_OS_WINDOWS) "rhubarb.exe" else "rhubarb"
		var currentDirectory: Path? = guiBinDirectory
		while (currentDirectory != null) {
			val candidate: Path = currentDirectory.resolve(rhubarbBinName)
			if (Files.exists(candidate)) {
				return@lazy candidate
			}
			currentDirectory = currentDirectory.parent
		}
		throw Exception("Could not find Rhubarb Lip Sync executable '$rhubarbBinName'."
			+ " Expected to find it in '$guiBinDirectory' or any directory above.")
	}

	private class TemporaryTextFile(val text: String) : AutoCloseable {
		val filePath: Path = Files.createTempFile(null, null).also {
			Files.write(it, text.toByteArray(StandardCharsets.UTF_8))
		}

		override fun close() {
			Files.delete(filePath)
		}

	}

	// Same as readLine, but can be interrupted.
	// Note that this function handles linebreak characters differently from readLine.
	// It only consumes the first linebreak character before returning and swallows any leading
	// linebreak characters.
	// This behavior is much easier to implement and doesn't make any difference for our purposes.
	private fun BufferedReader.interruptibleReadLine(): String {
		val result = StringBuilder()
		while (true) {
			val char = interruptibleReadChar()
			if (char == '\r' || char == '\n') {
				if (result.isNotEmpty()) return result.toString()
			} else {
				result.append(char)
			}
		}
	}

	private fun BufferedReader.interruptibleReadChar(): Char {
		while (true) {
			if (Thread.currentThread().isInterrupted) {
				throw InterruptedException()
			}
			if (ready()) {
				val result: Int = read()
				if (result == -1) {
					throw EOFException()
				}
				return result.toChar()
			}
			Thread.yield()
		}
	}
}
