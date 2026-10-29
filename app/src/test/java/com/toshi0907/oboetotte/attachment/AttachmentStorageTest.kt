package com.toshi0907.oboetotte.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentStorageTest {
    @Test
    fun safeExtension_acceptsAlphanumeric() {
        assertEquals("jpg", AttachmentStorage.safeExtension("photo.jpg"))
        assertEquals("tar", AttachmentStorage.safeExtension("archive.TAR.tar"))
        assertEquals("MP4", AttachmentStorage.safeExtension("movie.MP4"))
    }

    @Test
    fun safeExtension_fallsBackToEmptyForUnsafeValues() {
        assertEquals("", AttachmentStorage.safeExtension("a./x"))
        assertEquals("", AttachmentStorage.safeExtension("a.b\\c"))
        assertEquals("", AttachmentStorage.safeExtension("noext"))
        assertEquals("", AttachmentStorage.safeExtension("trailing."))
        assertEquals("", AttachmentStorage.safeExtension("a.j pg"))
        assertEquals("", AttachmentStorage.safeExtension("a.ｊｐｇ"))
        assertEquals("", AttachmentStorage.safeExtension("a.abcdefghijk"))
    }

    @Test
    fun providerAuthority_stripsUserId() {
        assertEquals("com.example.provider", AttachmentStorage.providerAuthority("com.example.provider"))
        assertEquals("com.example.provider", AttachmentStorage.providerAuthority("0@com.example.provider"))
        assertNull(AttachmentStorage.providerAuthority(null))
        assertNull(AttachmentStorage.providerAuthority("0@"))
    }

    @Test
    fun isOwnFileProviderAuthority_matchesOwnAuthorityOnly() {
        val pkg = "com.toshi0907.oboetotte"
        assertTrue(AttachmentStorage.isOwnFileProviderAuthority("com.toshi0907.oboetotte.fileprovider", pkg))
        assertTrue(AttachmentStorage.isOwnFileProviderAuthority("COM.toshi0907.oboetotte.FileProvider", pkg))
        assertFalse(AttachmentStorage.isOwnFileProviderAuthority("com.android.providers.media.documents", pkg))
    }
}
