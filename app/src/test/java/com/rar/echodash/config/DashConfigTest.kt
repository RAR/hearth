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
        assertEquals("argon", v.timerTone)
        assertEquals(80, v.timerVolume)
        // absent from JSON -> defaults, unknown-key tolerant
        val cfg = decodeConfig("""{"version":1,"voice":{"enabled":true}}""")
        assertEquals("argon", cfg.voice.timerTone)
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
    fun clampedNormalizesUnknownToneToArgon() {
        assertEquals("argon",
            DashConfig(voice = VoiceSettings(timerTone = "wobble")).clamped().voice.timerTone)
        assertEquals("argon",
            DashConfig(voice = VoiceSettings(timerTone = "   ")).clamped().voice.timerTone)
        // a synthesized tone survives (trimmed)
        assertEquals("trill",
            DashConfig(voice = VoiceSettings(timerTone = "  trill  ")).clamped().voice.timerTone)
        // the bundled system-alarm keys are all accepted
        for (t in listOf("argon", "oxygen", "krypton", "timer", "beep", "helium", "cyan")) {
            assertEquals(t, DashConfig(voice = VoiceSettings(timerTone = t)).clamped().voice.timerTone)
        }
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
    fun calendarPanelDefaultsEnabledLastForOldConfigs() {
        // Old document with no panels.calendar key -> enabled, ordered after cameras. Documents
        // still carrying the removed autoHideRail key decode fine (unknown keys are ignored).
        val cfg = decodeConfig(
            """{"version":1,"home":{"photoFolder":"nas"},"panelOptions":{"autoHideRail":true}}"""
        )
        assertEquals(true, cfg.panels.calendar.enabled)
        assertEquals(7, cfg.panels.calendar.order)
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

    @Test
    fun notificationsDefaults() {
        val n = DashConfig().notifications
        assertEquals(null, n.nwsAlerts)
        assertEquals("minor", n.nwsMinSeverity)
        // absent from JSON -> defaults, unknown-key tolerant
        val cfg = decodeConfig("""{"version":1}""")
        assertEquals(null, cfg.notifications.nwsAlerts)
        assertEquals("minor", cfg.notifications.nwsMinSeverity)
    }

    @Test
    fun notificationsRoundTrips() {
        val cfg = DashConfig(
            notifications = NotificationsConfig(nwsAlerts = "sensor.nws_alerts_alerts", nwsMinSeverity = "severe"),
        )
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals("sensor.nws_alerts_alerts", decodeConfig(text).notifications.nwsAlerts)
        assertEquals("severe", decodeConfig(text).notifications.nwsMinSeverity)
    }

    @Test
    fun notificationsClampedTrimsSensorAndClampsSeverity() {
        val cfg = DashConfig(
            notifications = NotificationsConfig(nwsAlerts = "  sensor.a  ", nwsMinSeverity = "  Moderate  "),
        ).clamped().notifications
        assertEquals("sensor.a", cfg.nwsAlerts)      // trimmed
        assertEquals("moderate", cfg.nwsMinSeverity) // lower-cased, kept
    }

    @Test
    fun notificationsClampedBlankSensorToNullAndBadSeverityToMinor() {
        val blank = DashConfig(
            notifications = NotificationsConfig(nwsAlerts = "   ", nwsMinSeverity = "bogus"),
        ).clamped().notifications
        assertEquals(null, blank.nwsAlerts)
        assertEquals("minor", blank.nwsMinSeverity)  // unknown -> minor
        val empty = DashConfig(
            notifications = NotificationsConfig(nwsAlerts = "", nwsMinSeverity = ""),
        ).clamped().notifications
        assertEquals(null, empty.nwsAlerts)
        assertEquals("minor", empty.nwsMinSeverity)
    }

    @Test
    fun notificationsAutoDismissDefaultsAndClamps() {
        val defaults = NotificationsConfig()
        assertEquals("off", defaults.autoDismiss)
        assertEquals(300, defaults.autoDismissSeconds)
        // absent from old JSON -> defaults
        val old = decodeConfig("""{"version":1}""").notifications
        assertEquals("off", old.autoDismiss)
        assertEquals(300, old.autoDismissSeconds)
        // trim/lower-case kept; unknown -> off; seconds coerced into 10..7200
        val c = DashConfig(
            notifications = NotificationsConfig(autoDismiss = "  Warning ", autoDismissSeconds = 3),
        ).clamped().notifications
        assertEquals("warning", c.autoDismiss)
        assertEquals(10, c.autoDismissSeconds)
        val bad = DashConfig(
            notifications = NotificationsConfig(autoDismiss = "bogus", autoDismissSeconds = 999_999),
        ).clamped().notifications
        assertEquals("off", bad.autoDismiss)
        assertEquals(7200, bad.autoDismissSeconds)
    }

    @Test
    fun notificationsSurvivesClampedAndDefaultsOnOldConfig() {
        assertEquals("severe",
            DashConfig(notifications = NotificationsConfig(nwsMinSeverity = "severe")).clamped().notifications.nwsMinSeverity)
        // old config document with no "notifications" key -> defaults fill in
        val cfg = decodeConfig("""{"version":1,"home":{"photoFolder":"nas"}}""")
        assertEquals(null, cfg.notifications.nwsAlerts)
        assertEquals("minor", cfg.notifications.nwsMinSeverity)
    }

    @Test
    fun referencedEntityIdsIncludesNwsSensor() {
        val cfg = DashConfig(
            entities = Entities(tempSensor = "sensor.t"),
            notifications = NotificationsConfig(nwsAlerts = "sensor.nws_alerts_alerts"),
        )
        assertEquals(listOf("sensor.t", "sensor.nws_alerts_alerts"), cfg.referencedEntityIds())
    }

    @Test
    fun rainEventClampsAndJoinsWatchList() {
        val cfg = DashConfig(entities = Entities(rainEvent = "  sensor.rain  ")).clamped()
        assertEquals("sensor.rain", cfg.entities.rainEvent)
        assertEquals(listOf("sensor.rain"), cfg.referencedEntityIds())
        assertEquals(null, DashConfig(entities = Entities(rainEvent = "   ")).clamped().entities.rainEvent)
        // Old stored docs without the key decode to the default.
        assertEquals(null, decodeConfig("""{"version":1}""").entities.rainEvent)
    }

    @Test
    fun calendarsDefaultEmptyAndRoundTrip() {
        assertEquals(emptyList<CalendarConfig>(), DashConfig().entities.calendars)
        // old config document with no "calendars" key -> defaults to empty
        val old = decodeConfig("""{"version":1}""")
        assertEquals(emptyList<CalendarConfig>(), old.entities.calendars)
        val cfg = DashConfig(
            entities = Entities(calendars = listOf(CalendarConfig("calendar.a", "Home", "teal"))),
        )
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        assertEquals("teal", decodeConfig(text).entities.calendars[0].color)
    }

    @Test
    fun calendarsClampedTrimColorValidatedBlankDroppedAndCapped() {
        val cals = DashConfig(
            entities = Entities(
                calendars = listOf(
                    CalendarConfig(entity = "  calendar.a  ", name = "  Personal  ", color = "  Green "),
                    CalendarConfig(entity = "calendar.b", name = "", color = "chartreuse"), // unknown -> blue
                    CalendarConfig(entity = "   ", name = "Blank", color = "red"),           // blank entity -> dropped
                    CalendarConfig(entity = "calendar.c"),
                    CalendarConfig(entity = "calendar.d"),
                    CalendarConfig(entity = "calendar.e"),
                    CalendarConfig(entity = "calendar.f"),
                    CalendarConfig(entity = "calendar.g"),                                    // 7th valid -> capped out
                ),
            ),
        ).clamped().entities.calendars
        assertEquals(6, cals.size)
        assertEquals(CalendarConfig(entity = "calendar.a", name = "Personal", color = "green"), cals[0])
        assertEquals("blue", cals[1].color) // unknown color normalized
        assertEquals(
            listOf("calendar.a", "calendar.b", "calendar.c", "calendar.d", "calendar.e", "calendar.f"),
            cals.map { it.entity },
        )
    }

    @Test
    fun calendarsAreNotWatchedEntities() {
        // Calendar events come from the service call, not state subscriptions, so calendars
        // must never enter the EntityHub watched set.
        val cfg = DashConfig(
            entities = Entities(
                tempSensor = "sensor.t",
                calendars = listOf(CalendarConfig("calendar.a"), CalendarConfig("calendar.b")),
            ),
        )
        assertEquals(listOf("sensor.t"), cfg.referencedEntityIds())
    }

    @Test
    fun solarTodayAndArrayFieldsRoundTrip() {
        val cfg = DashConfig(
            entities = Entities(
                solar = SolarConfig(
                    pv = "sensor.pv",
                    gridImportToday = "sensor.gi", gridExportToday = "sensor.ge",
                    battInToday = "sensor.bi", battOutToday = "sensor.bo",
                    arrays = listOf(
                        SolarArrayConfig(name = "South", power = "sensor.solar_array_a"),
                        SolarArrayConfig(power = "sensor.solar_array_b"),
                    ),
                ),
            ),
        )
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        // old configs (no keys) decode to defaults
        val old = decodeConfig("""{"version":1}""")
        assertEquals(emptyList<SolarArrayConfig>(), old.entities.solar.arrays)
        assertEquals(null, old.entities.solar.gridImportToday)
    }

    @Test
    fun clampedSolarArraysTrimmedDroppedAndCapped() {
        val cfg = DashConfig(
            entities = Entities(
                solar = SolarConfig(
                    gridImportToday = "  sensor.gi  ", battOutToday = "  ",
                    arrays = listOf(
                        SolarArrayConfig(name = "  South  ", power = "  sensor.a  "),
                        SolarArrayConfig(name = "", power = "sensor.b"),
                        SolarArrayConfig(name = "  ", power = "  "),          // all blank -> dropped
                        SolarArrayConfig(name = "Named", power = null),       // named, no power -> kept
                        SolarArrayConfig(name = "Fifth", power = "sensor.e"), // 5th valid -> capped out
                    ),
                ),
            ),
        ).clamped().entities.solar
        assertEquals("sensor.gi", cfg.gridImportToday)
        assertEquals(null, cfg.battOutToday)
        assertEquals(4, cfg.arrays.size)
        assertEquals(SolarArrayConfig("South", "sensor.a"), cfg.arrays[0])
        assertEquals(SolarArrayConfig("", "sensor.b"), cfg.arrays[1])
        assertEquals(SolarArrayConfig("Named", null), cfg.arrays[2])
        assertEquals(SolarArrayConfig("Fifth", "sensor.e"), cfg.arrays[3])
    }

    @Test
    fun referencedEntityIdsIncludeSolarTodayAndArrayPower() {
        val cfg = DashConfig(
            entities = Entities(
                solar = SolarConfig(
                    pv = "sensor.pv",
                    gridImportToday = "sensor.gi", gridExportToday = "sensor.ge",
                    battInToday = "sensor.bi", battOutToday = "sensor.bo",
                    arrays = listOf(
                        SolarArrayConfig(power = "sensor.a"),
                        SolarArrayConfig(name = "b-only-name"), // no power -> contributes nothing
                        SolarArrayConfig(power = "sensor.c"),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("sensor.pv", "sensor.gi", "sensor.ge", "sensor.bi", "sensor.bo", "sensor.a", "sensor.c"),
            cfg.referencedEntityIds(),
        )
    }

    @Test
    fun quickButtonsRoundTripAndDefault() {
        val cfg = DashConfig(
            entities = Entities(
                quickButtons = listOf(
                    QuickButtonConfig(name = "Desk", entity = "light.desk"),
                    QuickButtonConfig(entity = "script.movie_night"),
                ),
            ),
        )
        val text = ConfigJson.json.encodeToString(DashConfig.serializer(), cfg)
        assertEquals(cfg, decodeConfig(text))
        // old configs (no key) decode to an empty list
        val old = decodeConfig("""{"version":1}""")
        assertEquals(emptyList<QuickButtonConfig>(), old.entities.quickButtons)
    }

    @Test
    fun clampedQuickButtonsTrimDropEntitylessAndCap() {
        val cleaned = DashConfig(
            entities = Entities(
                quickButtons = listOf(
                    QuickButtonConfig(name = "  Desk  ", entity = "  light.desk  "),
                    QuickButtonConfig(name = "  ", entity = "switch.fan"),      // blank name kept, entity present
                    QuickButtonConfig(name = "Nameless", entity = "  "),        // blank entity -> dropped
                    QuickButtonConfig(name = "  ", entity = null),              // no entity -> dropped
                    QuickButtonConfig(name = "Two", entity = "input_boolean.a"),
                    QuickButtonConfig(name = "Three", entity = "button.b"),
                    QuickButtonConfig(name = "Fifth", entity = "scene.c"),      // 5th valid -> capped out
                ),
            ),
        ).clamped().entities.quickButtons
        assertEquals(4, cleaned.size)
        assertEquals(QuickButtonConfig("Desk", "light.desk"), cleaned[0])
        assertEquals(QuickButtonConfig("", "switch.fan"), cleaned[1])
        assertEquals(QuickButtonConfig("Two", "input_boolean.a"), cleaned[2])
        assertEquals(QuickButtonConfig("Three", "button.b"), cleaned[3])
    }

    @Test
    fun referencedEntityIdsIncludeQuickButtons() {
        val cfg = DashConfig(
            entities = Entities(
                quickButtons = listOf(
                    QuickButtonConfig(entity = "light.desk"),
                    QuickButtonConfig(name = "name-only"),   // no entity -> contributes nothing
                    QuickButtonConfig(entity = "scene.movie"),
                ),
            ),
        )
        assertEquals(listOf("light.desk", "scene.movie"), cfg.referencedEntityIds())
    }
}
