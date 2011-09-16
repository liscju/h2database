/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.dev.fs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.h2.engine.Constants;
import org.h2.message.DbException;
import org.h2.store.fs.FileObject;
import org.h2.store.fs.FileObjectInputStream;
import org.h2.store.fs.FilePath;
import org.h2.store.fs.FilePathDisk;
import org.h2.store.fs.FileUtils;
import org.h2.util.New;

/**
 * This is a read-only file system that allows to access databases stored in a
 * .zip or .jar file. The problem of this file system is that data is always
 * accessed as a stream. But unlike FileSystemZip, it is possible to stack file
 * systems.
 */
public class FilePathZip2 extends FilePath {

    /**
     * Register the file system.
     *
     * @return the instance
     */
    public static FilePathZip2 register() {
        FilePathZip2 instance = new FilePathZip2();
        FilePath.register(instance);
        return instance;
    }

    public FilePathZip2 getPath(String path) {
        FilePathZip2 p = new FilePathZip2();
        p.name = path;
        return p;
    }

    public void createDirectory() {
        // ignore
    }

    public boolean createFile() {
        throw DbException.getUnsupportedException("write");
    }

    public FilePath createTempFile(String suffix, boolean deleteOnExit, boolean inTempDir) throws IOException {
        if (!inTempDir) {
            throw new IOException("File system is read-only");
        }
        return new FilePathDisk().getPath(name).createTempFile(suffix, deleteOnExit, true);
    }

    public void delete() {
        throw DbException.getUnsupportedException("write");
    }

    public boolean exists() {
        try {
            String entryName = getEntryName();
            if (entryName.length() == 0) {
                return true;
            }
            ZipInputStream file = openZip();
            boolean result = false;
            while (true) {
                ZipEntry entry = file.getNextEntry();
                if (entry == null) {
                    break;
                }
                if (entry.getName().equals(entryName)) {
                    result = true;
                    break;
                }
                file.closeEntry();
            }
            file.close();
            return result;
        } catch (IOException e) {
            return false;
        }
    }

//    public boolean fileStartsWith(String fileName, String prefix) {
//        return fileName.startsWith(prefix);
//    }

    public long lastModified() {
        return 0;
    }

    public FilePath getParent() {
        int idx = name.lastIndexOf('/');
        return idx < 0 ? null : getPath(name.substring(0, idx));
    }

    public boolean isAbsolute() {
        return true;
    }

    public boolean isDirectory() {
        try {
            String entryName = getEntryName();
            if (entryName.length() == 0) {
                return true;
            }
            ZipInputStream file = openZip();
            boolean result = false;
            while (true) {
                ZipEntry entry = file.getNextEntry();
                if (entry == null) {
                    break;
                }
                String n = entry.getName();
                if (n.equals(entryName)) {
                    result = entry.isDirectory();
                    break;
                } else  if (n.startsWith(entryName)) {
                    if (n.length() == entryName.length() + 1) {
                        if (n.equals(entryName + "/")) {
                            result = true;
                            break;
                        }
                    }
                }
                file.closeEntry();
            }
            file.close();
            return result;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean canWrite() {
        return false;
    }

    public boolean setReadOnly() {
        return true;
    }

    public long size() {
        try {
            String entryName = getEntryName();
            ZipInputStream file = openZip();
            long result = 0;
            while (true) {
                ZipEntry entry = file.getNextEntry();
                if (entry == null) {
                    break;
                }
                if (entry.getName().equals(entryName)) {
                    result = entry.getSize();
                    if (result == -1) {
                        result = 0;
                        while (true) {
                            long x = file.skip(16 * Constants.IO_BUFFER_SIZE);
                            if (x == 0) {
                                break;
                            }
                            result += x;
                        }
                    }
                    break;
                }
                file.closeEntry();
            }
            file.close();
            return result;
        } catch (IOException e) {
            return 0;
        }
    }

    public ArrayList<FilePath> listFiles() {
        String path = name;
        try {
            if (path.indexOf('!') < 0) {
                path += "!";
            }
            if (!path.endsWith("/")) {
                path += "/";
            }
            ZipInputStream file = openZip();
            String dirName = getEntryName();
            String prefix = path.substring(0, path.length() - dirName.length());
            ArrayList<FilePath> list = New.arrayList();
            while (true) {
                ZipEntry entry = file.getNextEntry();
                if (entry == null) {
                    break;
                }
                String name = entry.getName();
                if (name.startsWith(dirName) && name.length() > dirName.length()) {
                    int idx = name.indexOf('/', dirName.length());
                    if (idx < 0 || idx >= name.length() - 1) {
                        list.add(getPath(prefix + name));
                    }
                }
                file.closeEntry();
            }
            file.close();
            return list;
        } catch (IOException e) {
            throw DbException.convertIOException(e, "listFiles " + path);
        }
    }

    public FilePath getCanonicalPath() {
        return this;
    }

    public InputStream newInputStream() throws IOException {
        FileObject file = openFileObject("r");
        return new FileObjectInputStream(file);
    }

    public FileObject openFileObject(String mode) throws IOException {
        String entryName = getEntryName();
        if (entryName.length() == 0) {
            throw new FileNotFoundException();
        }
        ZipInputStream in = openZip();
        while (true) {
            ZipEntry entry = in.getNextEntry();
            if (entry == null) {
                break;
            }
            if (entry.getName().equals(entryName)) {
                return new FileObjectZip2(name, entryName, in, size());
            }
            in.closeEntry();
        }
        in.close();
        throw new FileNotFoundException(name);
    }

    public OutputStream newOutputStream(boolean append) {
        throw DbException.getUnsupportedException("write");
    }

    public void moveTo(FilePath newName) {
        throw DbException.getUnsupportedException("write");
    }

//    public String unwrap(String fileName) {
//        if (fileName.startsWith(PREFIX)) {
//            fileName = fileName.substring(PREFIX.length());
//        }
//        int idx = fileName.indexOf('!');
//        if (idx >= 0) {
//            fileName = fileName.substring(0, idx);
//        }
//        return FileSystemDisk.expandUserHomeDirectory(fileName);
//    }

    private String getEntryName() {
        int idx = name.indexOf('!');
        String fileName;
        if (idx <= 0) {
            fileName = "";
        } else {
            fileName = name.substring(idx + 1);
        }
        fileName = fileName.replace('\\', '/');
        if (fileName.startsWith("/")) {
            fileName = fileName.substring(1);
        }
        return fileName;
    }

    private ZipInputStream openZip() throws IOException {
        String fileName = translateFileName(name);
        return new ZipInputStream(FileUtils.newInputStream(fileName));
    }

    private static String translateFileName(String fileName) {
        if (fileName.startsWith("zip2:")) {
            fileName = fileName.substring("zip2:".length());
        }
        int idx = fileName.indexOf('!');
        if (idx >= 0) {
            fileName = fileName.substring(0, idx);
        }
        return FilePathDisk.expandUserHomeDirectory(fileName);
    }

    public boolean fileStartsWith(String prefix) {
        return name.startsWith(prefix);
    }

    public String getScheme() {
        return "zip2";
    }

}