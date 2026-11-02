package com.agentum.attachment.application;

import java.io.ByteArrayInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/** 仅放行可确认的旧版 Word，避免把任意 OLE、加密 OOXML 或损坏文件当作兼容文档。 */
final class AttachmentFileFormat {

    private AttachmentFileFormat() {
    }

    static boolean isLegacyWord(byte[] bytes) {
        if (bytes.length < 8 || (bytes[0] & 0xff) != 0xd0 || (bytes[1] & 0xff) != 0xcf) return false;
        try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            var root = filesystem.getRoot();
            return root.hasEntry("WordDocument") && !root.hasEntry("EncryptedPackage")
                && (root.hasEntry("0Table") || root.hasEntry("1Table"));
        } catch (Exception exception) {
            return false;
        }
    }
}
