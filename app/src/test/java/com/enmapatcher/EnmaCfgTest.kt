package com.enmapatcher

import com.enmapatcher.model.EnmaCfg
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnmaCfgTest {

    @Test
    fun globMatching() {
        val cfg = EnmaCfg.fromJson(
            "{\"exclude\": [\"*.txt\", \"*.cfg\", \"img/\", \"assets/android/**\", \"exact/file.bin\", \"single?.dat\"]}"
        )
        assertFalse(cfg.allows("assets/data/file.txt"))
        assertFalse(cfg.allows("readme.cfg"))
        assertFalse(cfg.allows("assets/img/a.png"))
        assertFalse(cfg.allows("img/a.png"))
        assertFalse(cfg.allows("assets/android/x/y.bin"))
        assertFalse(cfg.allows("exact/file.bin"))
        assertFalse(cfg.allows("dir/single1.dat"))
        assertTrue(cfg.allows("assets/data/file.bin"))
        assertTrue(cfg.allows("assets/data/file.txt1"))
        assertTrue(cfg.allows("assets/android2/x.bin"))
        assertTrue(cfg.allows("dir/single12.dat"))
        val cfgIos = EnmaCfg.fromJson("{\"exclude_ios\": [\"assets/android/**\"]}")
        assertTrue(cfgIos.allows("assets/android/x.bin"))
        assertTrue(cfgIos.allows("assets/other/x.bin"))
        assertFalse(cfgIos.allows("assets/android/x.bin", android = false))
        assertTrue(cfgIos.allows("assets/other/x.bin", android = false))
        val cfgInclude = EnmaCfg.fromJson("{\"include\": [\"assets/data/event\"]}")
        assertTrue(cfgInclude.allows("assets/data/event/a.xq"))
        assertFalse(cfgInclude.allows("assets/data/map/a.bin"))
    }
}
