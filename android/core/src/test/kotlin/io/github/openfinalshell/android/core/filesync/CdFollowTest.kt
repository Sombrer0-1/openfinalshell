package io.github.openfinalshell.android.core.filesync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CdFollowTest {
    @Test fun parsesPlainRelativeAndQuotedTargets() {
        assertEquals("/var/log", CdFollow.parseCdTarget("cd /var/log"))
        assertEquals("..", CdFollow.parseCdTarget("cd .."))
        assertEquals("./x", CdFollow.parseCdTarget("cd ./x"))
        assertEquals("logs", CdFollow.parseCdTarget("  cd   logs  "))
        assertEquals("~", CdFollow.parseCdTarget("cd"))
        assertEquals("~", CdFollow.parseCdTarget("cd  "))
        assertEquals("~", CdFollow.parseCdTarget("cd ~"))
        assertEquals("~/logs", CdFollow.parseCdTarget("cd ~/logs"))
        assertEquals("my dir", CdFollow.parseCdTarget("cd \"my dir\""))
        assertEquals("/opt/a b", CdFollow.parseCdTarget("cd '/opt/a b'"))
        assertEquals("my dir", CdFollow.parseCdTarget("cd my\\ dir"))
    }

    @Test fun readsOnlyTheFirstCommandAndSkipsCdOptions() {
        assertEquals("/tmp", CdFollow.parseCdTarget("cd /tmp && ls -la"))
        assertEquals("/tmp", CdFollow.parseCdTarget("cd /tmp; ls"))
        assertEquals("/tmp", CdFollow.parseCdTarget("cd /tmp | cat"))
        assertNull(CdFollow.parseCdTarget("ls && cd /tmp"))
        assertEquals("a;b", CdFollow.parseCdTarget("cd \"a;b\""))
        assertEquals("/srv", CdFollow.parseCdTarget("cd -P /srv"))
        assertEquals("-weird-dir", CdFollow.parseCdTarget("cd -- -weird-dir"))
    }

    @Test fun refusesOldpwdVariablesBackticksAndLookalikes() {
        assertNull(CdFollow.parseCdTarget("cd -"))
        assertNull(CdFollow.parseCdTarget("cd \$HOME/logs"))
        assertNull(CdFollow.parseCdTarget("cd \"\$DIR\""))
        assertNull(CdFollow.parseCdTarget("cd `pwd`/x"))
        assertNull(CdFollow.parseCdTarget("ls -la"))
        assertNull(CdFollow.parseCdTarget("cdx /tmp"))
        assertNull(CdFollow.parseCdTarget("echo cd /tmp"))
    }

    @Test fun applyCdNormalizesAbsoluteRelativeAndHome() {
        assertEquals("/var/log", CdFollow.applyCd("/root", "/var/log/", null))
        assertEquals("/a/c", CdFollow.applyCd("/root", "/a/./b/../c", null))
        assertEquals("/var/www/logs", CdFollow.applyCd("/var/www", "logs", null))
        assertEquals("/var", CdFollow.applyCd("/var/www", "..", null))
        assertEquals("/etc", CdFollow.applyCd("/var/www", "../../etc", null))
        assertEquals("/", CdFollow.applyCd("/", "..", null))
        assertEquals("/home/me", CdFollow.applyCd("/tmp", "~", "/home/me"))
        assertEquals("/home/me/logs", CdFollow.applyCd("/tmp", "~/logs", "/home/me"))
        assertNull(CdFollow.applyCd("/tmp", "~", null))
        assertNull(CdFollow.applyCd("/tmp", "~/logs", null))
        assertNull(CdFollow.applyCd("/tmp", "~root", "/home/me"))
        assertNull(CdFollow.applyCd("", "logs", null))
    }

    @Test fun normalizePosixCollapsesDotsAndStopsAtRoot() {
        assertEquals("/a/b", CdFollow.normalizePosix("/a//b/"))
        assertEquals("/", CdFollow.normalizePosix("/../.."))
        assertEquals("/", CdFollow.normalizePosix("/"))
    }

    @Test fun sameDirectoryRefreshIsNotAPendingNavigation() {
        assertEquals(NavView("/var/log", false), CdFollow.navView("/var/log", null))
        assertEquals(NavView("/etc", true), CdFollow.navView("/var/log", "/etc"))
        assertEquals(NavView("/var/log", false), CdFollow.navView("/var/log", "/var/log"))
        assertEquals(NavView("/root", true), CdFollow.navView("", "/root"))
        assertFalse(CdFollow.navView("/var/log", "/var/log").navPending)
        assertTrue(CdFollow.navView("", "/root").navPending)
    }
}
