package com.uviewer_android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory

class BackupRulesTest {
    @Test fun encryptedCredentialsAreExcludedFromAllBackupModes() {
        val base = listOf(File("src/main/res/xml"), File("app/src/main/res/xml")).first { it.isDirectory }
        val xpath = XPathFactory.newInstance().newXPath()
        for ((file, scope) in listOf("backup_rules.xml" to "full-backup-content", "data_extraction_rules.xml" to "data-extraction-rules/cloud-backup", "data_extraction_rules.xml" to "data-extraction-rules/device-transfer")) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(base, file))
            assertEquals("1", xpath.evaluate("count(/$scope/exclude[@domain='sharedpref' and @path='uviewer_secure_prefs.xml'])", doc))
        }
    }
}
