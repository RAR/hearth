package com.rar.echodash.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashConfigTest {

    @Test
    fun roundTripsThroughJson() {
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "sensor.t",
                weather = "weather.home",
                climate = listOf("climate.hall"),
                solar = SolarConfig(pv = "sensor.pv"),
                lightGroups = listOf(LightGroup("Lights", listOf("light.k"))),
            ),
        )
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
    }

    @Test
    fun defaultsFillMissingFieldsAndUnknownKeysIgnored() {
        val cfg = decodeConfig("""{"version":1,"whatIsThis":true,"home":{"photoFolder":"nas"}}""")
        assertEquals(1, cfg.version)
        assertEquals("nas", cfg.home.photoFolder)
        assertEquals(60, cfg.home.idleReturnSeconds)       // default
        assertEquals(ClockFormat.AUTO, cfg.home.clockFormat) // default
        assertEquals(0.5, cfg.panelOptions.thermostatStep, 0.0)
        assertTrue(cfg.panels.lights.enabled)
    }

    @Test
    fun clampsOutOfRangeNumbers() {
        val cfg = DashConfig(
            home = HomeSettings(idleReturnSeconds = 5, photoCacheCap = 999, slideshowSeconds = 9999),
            panelOptions = PanelOptions(thermostatStep = 12.0, forecastDays = 9, sensorDecimals = 8),
        ).clamped()
        assertEquals(15, cfg.home.idleReturnSeconds)   // floor 15
        assertEquals(500, cfg.home.photoCacheCap)      // ceil 500
        assertEquals(5.0, cfg.panelOptions.thermostatStep, 0.0) // ceil 5.0
        assertEquals(5, cfg.panelOptions.forecastDays)  // ceil 5
        assertEquals(3, cfg.panelOptions.sensorDecimals) // ceil 3
        assertEquals(3600, cfg.home.slideshowSeconds)   // ceil 3600

        val low = DashConfig(
            home = HomeSettings(idleReturnSeconds = 9000, photoCacheCap = 1, slideshowSeconds = 1),
            panelOptions = PanelOptions(thermostatStep = 0.0, forecastDays = 0, sensorDecimals = -1),
        ).clamped()
        assertEquals(3600, low.home.idleReturnSeconds)
        assertEquals(5, low.home.photoCacheCap)
        assertEquals(0.1, low.panelOptions.thermostatStep, 0.0001)
        assertEquals(1, low.panelOptions.forecastDays)
        assertEquals(0, low.panelOptions.sensorDecimals)
        assertEquals(10, low.home.slideshowSeconds)     // floor 10
    }

    @Test
    fun clampedStripsBlankClimateIds() {
        val cfg = DashConfig(
            entities = Entities(climate = listOf("climate.hall", "", "  ")),
        ).clamped()
        assertEquals(listOf("climate.hall"), cfg.entities.climate)
    }

    @Test
    fun clampedStripsBlankLightGroupEntities() {
        val cfg = DashConfig(
            entities = Entities(
                lightGroups = listOf(LightGroup("Kitchen", listOf("light.k", "", " "))),
            ),
        ).clamped()
        assertEquals(listOf(LightGroup("Kitchen", listOf("light.k"))), cfg.entities.lightGroups)
    }

    @Test
    fun clampedDropsUnnamedLightGroupsThatBecomeEmpty() {
        val cfg = DashConfig(
            entities = Entities(
                lightGroups = listOf(
                    LightGroup("", listOf("", " ")), // blank name, all entities blank -> dropped
                    LightGroup("Kept", listOf("")),   // named, entities become empty -> kept
                    LightGroup("", emptyList()),      // blank name, already empty -> dropped
                ),
            ),
        ).clamped()
        assertEquals(listOf(LightGroup("Kept", emptyList())), cfg.entities.lightGroups)
    }

    @Test
    fun clampedMapsBlankSingleSlotsToNull() {
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "  ",
                weather = "",
                solar = SolarConfig(pv = "sensor.pv", load = "", grid = "  ", pvToday = null, loadToday = "x"),
            ),
        ).clamped()
        assertEquals(null, cfg.entities.tempSensor)
        assertEquals(null, cfg.entities.weather)
        assertEquals("sensor.pv", cfg.entities.solar.pv)
        assertEquals(null, cfg.entities.solar.load)
        assertEquals(null, cfg.entities.solar.grid)
        assertEquals(null, cfg.entities.solar.pvToday)
        assertEquals("x", cfg.entities.solar.loadToday)
    }

    @Test
    fun solarBattFieldsClampedAndTrimmed() {
        val cfg = DashConfig(
            entities = Entities(
                solar = SolarConfig(battSoc = " sensor.soc ", battPower = ""),
            ),
        ).clamped()
        assertEquals("sensor.soc", cfg.entities.solar.battSoc)
        assertEquals(null, cfg.entities.solar.battPower)
    }

    @Test
    fun referencedEntityIdsCollectsEverySlotDistinct() {
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "sensor.t",
                weather = "weather.home",
                climate = listOf("climate.hall", "climate.hall"),
                solar = SolarConfig(pv = "sensor.pv", grid = "sensor.grid", battSoc = "sensor.soc", battPower = "sensor.batt"),
                lightGroups = listOf(
                    LightGroup("A", listOf("light.k", "sensor.t")), // sensor.t dup with tempSensor
                    LightGroup("B", listOf("light.l")),
                ),
            ),
        )
        assertEquals(
            listOf("sensor.t", "weather.home", "climate.hall", "sensor.pv", "sensor.grid", "sensor.soc", "sensor.batt", "light.k", "light.l"),
            cfg.referencedEntityIds(),
        )
    }

    @Test
    fun roundTripsCamerasAndDoorbells() {
        val cfg = DashConfig(
            entities = Entities(
                cameras = listOf(
                    CameraConfig(name = "Front Door", entity = "camera.front_door_fluent",
                        rtspUrl = "rtsp://frigate:8554/front_door_bell"),
                    CameraConfig(name = "Printer", entity = "camera.p1s"),
                ),
                doorbells = listOf(DoorbellConfig(trigger = "binary_sensor.front_door_visitor", camera = "Front Door")),
            ),
            panelOptions = PanelOptions(doorbellPopupSeconds = 45),
        )
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
    }

    @Test
    fun camerasPanelDefaultsDisabledAtOrderSix() {
        val cfg = DashConfig()
        assertEquals(false, cfg.panels.cameras.enabled)
        assertEquals(6, cfg.panels.cameras.order)
        assertEquals(30, cfg.panelOptions.doorbellPopupSeconds) // default
    }

    @Test
    fun clampedNormalizesCameras() {
        val cfg = DashConfig(
            entities = Entities(
                cameras = listOf(
                    CameraConfig(name = "  Front Door  ", entity = "  camera.fd  ", rtspUrl = "  "),
                    CameraConfig(name = "RtspOnly", entity = null, rtspUrl = " rtsp://h/x "),
                    CameraConfig(name = "  ", entity = "camera.blankname"), // blank name -> dropped
                    CameraConfig(name = "NoStream", entity = "", rtspUrl = ""), // no entity/url -> dropped
                ),
            ),
        ).clamped()
        assertEquals(2, cfg.entities.cameras.size)
        assertEquals(CameraConfig("Front Door", "camera.fd", null), cfg.entities.cameras[0])
        assertEquals(CameraConfig("RtspOnly", null, "rtsp://h/x"), cfg.entities.cameras[1])
    }

    @Test
    fun clampedDropsDoorbellsWithBlankTriggerOrUnknownCamera() {
        val cfg = DashConfig(
            entities = Entities(
                cameras = listOf(CameraConfig(name = "Front Door", rtspUrl = "rtsp://h/fd")),
                doorbells = listOf(
                    DoorbellConfig(trigger = " binary_sensor.v ", camera = " Front Door "), // trimmed, kept
                    DoorbellConfig(trigger = "  ", camera = "Front Door"),                   // blank trigger -> dropped
                    DoorbellConfig(trigger = "binary_sensor.x", camera = "Ghost"),           // unknown camera -> dropped
                ),
            ),
        ).clamped()
        assertEquals(listOf(DoorbellConfig("binary_sensor.v", "Front Door")), cfg.entities.doorbells)
    }

    @Test
    fun clampedCoercesDoorbellPopupSeconds() {
        assertEquals(120, DashConfig(panelOptions = PanelOptions(doorbellPopupSeconds = 999)).clamped().panelOptions.doorbellPopupSeconds)
        assertEquals(5, DashConfig(panelOptions = PanelOptions(doorbellPopupSeconds = 1)).clamped().panelOptions.doorbellPopupSeconds)
    }

    @Test
    fun referencedEntityIdsIncludesCameraEntitiesAndDoorbellTriggers() {
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "sensor.t",
                cameras = listOf(
                    CameraConfig(name = "Front Door", entity = "camera.fd", rtspUrl = "rtsp://h/fd"),
                    CameraConfig(name = "RtspOnly", rtspUrl = "rtsp://h/x"), // no entity -> contributes nothing
                ),
                doorbells = listOf(DoorbellConfig(trigger = "binary_sensor.v", camera = "Front Door")),
            ),
        )
        assertEquals(listOf("sensor.t", "camera.fd", "binary_sensor.v"), cfg.referencedEntityIds())
    }

    @Test
    fun voiceDefaultsOff() {
        assertEquals(false, DashConfig().voice.enabled)
        // absent from JSON -> default off, unknown-key tolerant
        val cfg = decodeConfig("""{"version":1}""")
        assertEquals(false, cfg.voice.enabled)
    }

    @Test
    fun voiceRoundTrips() {
        val cfg = DashConfig(voice = VoiceSettings(enabled = true))
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals(true, decodeConfig(text).voice.enabled)
    }

    @Test
    fun voiceSurvivesClamped() {
        assertEquals(true, DashConfig(voice = VoiceSettings(enabled = true)).clamped().voice.enabled)
    }

    @Test
    fun voiceTimerDefaults() {
        val v = DashConfig().voice
        assertEquals("twotone", v.timerTone)
        assertEquals(80, v.timerVolume)
        // absent from JSON -> defaults, unknown-key tolerant
        val cfg = decodeConfig("""{"version":1,"voice":{"enabled":true}}""")
        assertEquals("twotone", cfg.voice.timerTone)
        assertEquals(80, cfg.voice.timerVolume)
    }

    @Test
    fun voiceTimerRoundTrips() {
        val cfg = DashConfig(voice = VoiceSettings(enabled = true, timerTone = "chime", timerVolume = 45))
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals("chime", decodeConfig(text).voice.timerTone)
        assertEquals(45, decodeConfig(text).voice.timerVolume)
    }

    @Test
    fun clampedNormalizesUnknownToneToTwotone() {
        assertEquals("twotone",
            DashConfig(voice = VoiceSettings(timerTone = "wobble")).clamped().voice.timerTone)
        assertEquals("twotone",
            DashConfig(voice = VoiceSettings(timerTone = "   ")).clamped().voice.timerTone)
        // a known tone survives (trimmed)
        assertEquals("trill",
            DashConfig(voice = VoiceSettings(timerTone = "  trill  ")).clamped().voice.timerTone)
    }

    @Test
    fun clampedCoercesTimerVolume() {
        assertEquals(100, DashConfig(voice = VoiceSettings(timerVolume = 250)).clamped().voice.timerVolume)
        assertEquals(0, DashConfig(voice = VoiceSettings(timerVolume = -5)).clamped().voice.timerVolume)
        assertEquals(45, DashConfig(voice = VoiceSettings(timerVolume = 45)).clamped().voice.timerVolume)
    }

    @Test
    fun wakeSoundVolumeDefaultsAndClamps() {
        assertEquals(80, DashConfig().voice.wakeSoundVolume)
        // absent from JSON -> default, unknown-key tolerant
        val cfg = decodeConfig("""{"version":1,"voice":{"enabled":true}}""")
        assertEquals(80, cfg.voice.wakeSoundVolume)
        assertEquals(100, DashConfig(voice = VoiceSettings(wakeSoundVolume = 150)).clamped().voice.wakeSoundVolume)
        assertEquals(0, DashConfig(voice = VoiceSettings(wakeSoundVolume = -5)).clamped().voice.wakeSoundVolume)
    }

    @Test
    fun mediaDefaultsToNoCompanion() {
        assertEquals(null, DashConfig().media.companionEntity)
        // absent from JSON -> default, unknown-key tolerant
        val cfg = decodeConfig("""{"version":1}""")
        assertEquals(null, cfg.media.companionEntity)
    }

    @Test
    fun mediaRoundTrips() {
        val cfg = DashConfig(media = MediaSettings(companionEntity = "media_player.ma_echo"))
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals("media_player.ma_echo", decodeConfig(text).media.companionEntity)
    }

    @Test
    fun clampedTrimsBlankCompanionToNull() {
        assertEquals(null, DashConfig(media = MediaSettings(companionEntity = "  ")).clamped().media.companionEntity)
        assertEquals(null, DashConfig(media = MediaSettings(companionEntity = "")).clamped().media.companionEntity)
        assertEquals("media_player.x",
            DashConfig(media = MediaSettings(companionEntity = "  media_player.x  ")).clamped().media.companionEntity)
    }

    @Test
    fun referencedEntityIdsIncludesCompanionMediaPlayer() {
        val cfg = DashConfig(
            entities = Entities(tempSensor = "sensor.t"),
            media = MediaSettings(companionEntity = "media_player.ma_echo"),
        )
        assertEquals(listOf("sensor.t", "media_player.ma_echo"), cfg.referencedEntityIds())
    }

    @Test
    fun pausedDismissDefaultsTo60AndRoundTrips() {
        assertEquals(60, DashConfig().media.pausedDismissSeconds)
        val cfg = DashConfig(media = MediaSettings(companionEntity = null, pausedDismissSeconds = 120))
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(120, decodeConfig(text).media.pausedDismissSeconds)
    }

    @Test
    fun pausedDismissClampsToRange() {
        assertEquals(5, DashConfig(media = MediaSettings(pausedDismissSeconds = 0)).clamped().media.pausedDismissSeconds)
        assertEquals(3600, DashConfig(media = MediaSettings(pausedDismissSeconds = 99999)).clamped().media.pausedDismissSeconds)
    }

    @Test
    fun nightDefaults() {
        val n = DashConfig().night
        assertEquals(false, n.enabled)
        assertEquals(10, n.thresholdLux)
        assertEquals(0, n.brightness)
        // absent from JSON -> defaults, unknown-key tolerant
        val cfg = decodeConfig("""{"version":1}""")
        assertEquals(false, cfg.night.enabled)
        assertEquals(10, cfg.night.thresholdLux)
        assertEquals(0, cfg.night.brightness)
    }

    @Test
    fun nightRoundTrips() {
        val cfg = DashConfig(night = NightSettings(enabled = true, thresholdLux = 25, brightness = 3))
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals(true, decodeConfig(text).night.enabled)
        assertEquals(25, decodeConfig(text).night.thresholdLux)
        assertEquals(3, decodeConfig(text).night.brightness)
    }

    @Test
    fun nightClampsBounds() {
        val hi = DashConfig(night = NightSettings(thresholdLux = 5000, brightness = 250)).clamped().night
        assertEquals(1000, hi.thresholdLux)   // ceil 1000
        assertEquals(100, hi.brightness)       // ceil 100
        val lo = DashConfig(night = NightSettings(thresholdLux = 0, brightness = -5)).clamped().night
        assertEquals(1, lo.thresholdLux)       // floor 1
        assertEquals(0, lo.brightness)          // floor 0
    }

    @Test
    fun nightSurvivesClampedAndDefaultsOnOldConfig() {
        assertEquals(true, DashConfig(night = NightSettings(enabled = true)).clamped().night.enabled)
        // old config document with no "night" key -> defaults fill in
        val cfg = decodeConfig("""{"version":1,"home":{"photoFolder":"nas"}}""")
        assertEquals(false, cfg.night.enabled)
        assertEquals(10, cfg.night.thresholdLux)
    }

    @Test
    fun evSlotsClampedTrimmedAndCapped() {
        val cfg = DashConfig(
            entities = Entities(
                evs = listOf(
                    EvConfig(name = "  Ioniq  ", plugged = "  binary_sensor.plug  ", charging = "  binary_sensor.charging  ",
                        soc = "sensor.soc", limit = "  sensor.limit  ", power = "  ", energy = "  sensor.energy  ", eta = null),
                    EvConfig(name = "", plugged = "", charging = "switch.c2", soc = "", power = null, energy = " ", eta = "  "),
                    EvConfig(name = "Kona", charging = "sensor.c3"),                 // 3rd valid -> capped out
                    EvConfig(name = "   ", plugged = "  ", charging = "  ", soc = " ", power = null, energy = "", eta = ""), // all blank -> dropped
                ),
            ),
        ).clamped()
        assertEquals(2, cfg.entities.evs.size)
        assertEquals(
            EvConfig(name = "Ioniq", plugged = "binary_sensor.plug", charging = "binary_sensor.charging",
                soc = "sensor.soc", limit = "sensor.limit", power = null, energy = "sensor.energy", eta = null),
            cfg.entities.evs[0],
        )
        assertEquals(EvConfig(name = "", charging = "switch.c2"), cfg.entities.evs[1])
    }

    @Test
    fun referencedEntityIdsIncludeEvEntities() {
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "sensor.t",
                evs = listOf(
                    EvConfig(name = "Ioniq", plugged = "binary_sensor.plug", charging = "binary_sensor.charging",
                        soc = "sensor.soc", limit = "sensor.limit", power = "sensor.power", energy = "sensor.energy", eta = "sensor.eta"),
                    EvConfig(charging = "switch.c2"),
                ),
            ),
        )
        assertEquals(
            listOf("sensor.t", "binary_sensor.plug", "binary_sensor.charging", "sensor.soc", "sensor.limit",
                "sensor.power", "sensor.energy", "sensor.eta", "switch.c2"),
            cfg.referencedEntityIds(),
        )
    }

    @Test
    fun autoHideRailDefaultsFalseAndSurvivesClamped() {
        // old config document with no "autoHideRail" key -> defaults to false
        val cfg = decodeConfig("""{"version":1,"home":{"photoFolder":"nas"}}""")
        assertEquals(false, cfg.panelOptions.autoHideRail)
        assertEquals(true, DashConfig(panelOptions = PanelOptions(autoHideRail = true)).clamped().panelOptions.autoHideRail)
    }

    @Test
    fun wakeWordDefaultsAndClamps() {
        assertEquals("okay_nabu", DashConfig().voice.wakeWord)
        val cfg = decodeConfig("""{"version":1,"voice":{"enabled":true}}""")
        assertEquals("okay_nabu", cfg.voice.wakeWord)               // old config -> default
        assertEquals("hey_jarvis", DashConfig(voice = VoiceSettings(wakeWord = "hey_jarvis")).clamped().voice.wakeWord)
        assertEquals("alexa", DashConfig(voice = VoiceSettings(wakeWord = "  alexa  ")).clamped().voice.wakeWord) // trimmed
        assertEquals("okay_nabu", DashConfig(voice = VoiceSettings(wakeWord = "bogus")).clamped().voice.wakeWord) // unknown -> default
    }

    @Test
    fun wakeThresholdDefaultsAndClamps() {
        assertEquals(50, DashConfig().voice.wakeThreshold)
        val cfg = decodeConfig("""{"version":1,"voice":{"enabled":true}}""")
        assertEquals(50, cfg.voice.wakeThreshold)                  // old config -> default
        assertEquals(95, DashConfig(voice = VoiceSettings(wakeThreshold = 200)).clamped().voice.wakeThreshold) // ceil 95
        assertEquals(10, DashConfig(voice = VoiceSettings(wakeThreshold = 1)).clamped().voice.wakeThreshold)   // floor 10
        assertEquals(70, DashConfig(voice = VoiceSettings(wakeThreshold = 70)).clamped().voice.wakeThreshold)
    }

    @Test
    fun wakeSettingsRoundTrip() {
        val cfg = DashConfig(voice = VoiceSettings(enabled = true, wakeWord = "alexa", wakeThreshold = 65))
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals("alexa", decodeConfig(text).voice.wakeWord)
        assertEquals(65, decodeConfig(text).voice.wakeThreshold)
    }
}
