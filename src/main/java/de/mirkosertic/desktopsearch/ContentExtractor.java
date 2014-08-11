/**
 * FreeDesktopSearch - A Search Engine for your Desktop
 * Copyright (C) 2013 Mirko Sertic
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, see <http://www.gnu.org/licenses/>.
 */
package de.mirkosertic.desktopsearch;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Set;

public class ContentExtractor {

    private final Set<String> supportedExtensions;
    private final Tika tika;

    public ContentExtractor() {
        supportedExtensions = new HashSet<>();
        supportedExtensions.add("txt");
        supportedExtensions.add("msg");
        supportedExtensions.add("pdf");
        supportedExtensions.add("doc");
        supportedExtensions.add("docx");
        supportedExtensions.add("ppt");
        supportedExtensions.add("pptx");
        supportedExtensions.add("rtf");
        supportedExtensions.add("html");

        tika = new Tika();
    }

    public Content extractContentFrom(Path aFile, BasicFileAttributes aBasicFileAttributes) {
        try {
            Metadata theMetaData = new Metadata();

            String theStringData;
            // Files under 10 Meg are read into memory as a whole
            if (aBasicFileAttributes.size() < 1024 * 1024 * 4) {
                byte[] theData = Files.readAllBytes(aFile);
                theStringData = tika.parseToString(new ByteArrayInputStream(theData), theMetaData);
            } else {
                try (InputStream theStream = Files.newInputStream(aFile, StandardOpenOption.READ)) {
                    theStringData = tika.parseToString(new BufferedInputStream(theStream), theMetaData);
                }
            }

            FileTime theFileTime = aBasicFileAttributes.lastModifiedTime();
            Content theContent = new Content(aFile.toString(), theStringData, aBasicFileAttributes.size(), theFileTime.toMillis());
            for (String theName : theMetaData.names()) {
                theContent.addMetaData(theName.toLowerCase(), theMetaData.get(theName));
            }

            String theFileName = aFile.toString();
            int p = theFileName.lastIndexOf(".");
            if (p > 0) {
                String theExtension = theFileName.substring(p + 1);
                theContent.addMetaData(IndexFields.EXTENSION, theExtension);
            }

            return theContent;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public boolean supportsFile(String aFilename) {
        int p = aFilename.lastIndexOf(".");
        if (p < 0) {
            return false;
        }
        String theExtension = aFilename.substring(p + 1);
        return supportedExtensions.contains(theExtension.toLowerCase());
    }
}