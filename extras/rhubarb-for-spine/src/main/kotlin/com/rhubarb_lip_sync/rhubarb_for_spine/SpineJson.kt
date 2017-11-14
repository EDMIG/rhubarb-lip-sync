package com.rhubarb_lip_sync.rhubarb_for_spine

import com.beust.klaxon.*
import java.nio.file.Files
import java.nio.file.Path

class SpineJson(val filePath: Path) {
	val fileDirectoryPath: Path = filePath.parent
	val json: JsonObject
	private val skeleton: JsonObject
	private val defaultSkin: JsonObject

	init {
		if (!Files.exists(filePath)) {
			throw Exception("File '$filePath' does not exist.")
		}
		try {
			json = Parser().parse(filePath.toString()) as JsonObject
		} catch (e: Exception) {
			throw Exception("Wrong file format. This is not a valid JSON file.")
		}
		skeleton = json.obj("skeleton") ?: throw Exception("JSON file is corrupted.")
		val skins = json.obj("skins") ?: throw Exception("JSON file doesn't contain skins.")
		defaultSkin = skins.obj("default") ?: throw Exception("JSON file doesn't have a default skin.")
		validateProperties()
	}

	private fun validateProperties() {
		imagesDirectoryPath
		audioDirectoryPath
	}

	val imagesDirectoryPath: Path get() {
		val relativeImagesDirectory = skeleton.string("images")
			?: throw Exception("JSON file is incomplete: Images path is missing."
				+ "Make sure to check 'Nonessential data' when exporting.")

		val imagesDirectoryPath = fileDirectoryPath.resolve(relativeImagesDirectory)
		if (!Files.exists(imagesDirectoryPath)) {
			throw Exception("Could not find images directory relative to the JSON file."
				+ " Make sure the JSON file is in the same directory as the original Spine file.")
		}

		return imagesDirectoryPath
	}

	val audioDirectoryPath: Path get() {
		val relativeAudioDirectory = skeleton.string("audio")
			?: throw Exception("JSON file is incomplete: Audio path is missing."
			+ "Make sure to check 'Nonessential data' when exporting.")

		val audioDirectoryPath = fileDirectoryPath.resolve(relativeAudioDirectory)
		if (!Files.exists(audioDirectoryPath)) {
			throw Exception("Could not find audio directory relative to the JSON file."
				+ " Make sure the JSON file is in the same directory as the original Spine file.")
		}

		return audioDirectoryPath
	}

	val frameRate: Double get() {
		return skeleton.double("fps") ?: 30.0
	}

	val slots: List<String> get() {
		val slots = json.array("slots") ?: listOf<JsonObject>()
		return slots.mapNotNull { it.string("name") }
	}

	val presumedMouthSlot: String? get() {
		return slots.firstOrNull { it.contains("mouth", ignoreCase = true) }
			?: slots.firstOrNull()
	}

	data class AudioEvent(val name: String, val relativeAudioFilePath: String, val dialog: String?)

	val audioEvents: List<AudioEvent> get() {
		val events = json.obj("events") ?: JsonObject()
		val result = mutableListOf<AudioEvent>()
		for ((name, value) in events) {
			if (value !is JsonObject) throw Exception("Invalid event found.")

			val relativeAudioFilePath = value.string("audio") ?: continue

			val dialog = value.string("string")
			result.add(AudioEvent(name, relativeAudioFilePath, dialog))
		}
		return result
	}

	fun getSlotAttachmentNames(slotName: String): List<String> {
		val attachments = defaultSkin.obj(slotName) ?: JsonObject()
		return attachments.map { it.key }
	}

	fun hasAnimation(animationName: String): Boolean {
		val animations = json.obj("animations") ?: return false
		return animations.any { it.key == animationName }
	}

	fun createOrUpdateAnimation(mouthCues: List<MouthCue>, eventName: String, animationName: String,
		mouthSlot: String, mouthNaming: MouthNaming
	) {
		if (!json.containsKey("animations")) {
			json["animations"] = JsonObject()
		}
		val animations: JsonObject = json.obj("animations")!!

		// Round times to full frames. Always round down.
		// If events coincide, prefer the latest one.
		val keyframes = mutableMapOf<Int, MouthShape>()
		for (mouthCue in mouthCues) {
			val frameNumber = (mouthCue.time * frameRate).toInt()
			keyframes[frameNumber] = mouthCue.mouthShape
		}

		animations[animationName] = JsonObject().apply {
			this["slots"] = JsonObject().apply {
				this[mouthSlot] = JsonObject().apply {
					this["attachment"] = JsonArray(
						keyframes
							.toSortedMap()
							.map { (frameNumber, mouthShape) ->
								JsonObject().apply {
									this["time"] = frameNumber / frameRate
									this["name"] = mouthNaming.getName(mouthShape)
								}
							}
					)
				}
			}
			this["events"] = JsonArray(
				JsonObject().apply {
					this["time"] = 0.0
					this["name"] = eventName
					this["string"] = ""
				}
			)
		}
	}
}