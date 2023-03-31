/*
 * Copyright Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.lucko.jarrelocator;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import java.util.regex.Pattern;

/**
 * A task that copies {@link JarEntry jar entries} from a {@link JarFile jar input} to a
 * {@link JarOutputStream jar output}, applying the relocations defined by a
 * {@link RelocatingRemapper}.
 */
final class JarRelocatorTask {

    /**
     * META-INF/*.SF
     * META-INF/*.DSA
     * META-INF/*.RSA
     * META-INF/SIG-*
     *
     * <a href="https://docs.oracle.com/en/java/javase/11/docs/specs/jar/jar.html#signed-jar-file">Specification</a>
     */
    private static final Pattern SIGNATURE_FILE_PATTERN = Pattern.compile("META-INF/(?:[^/]+\\.(?:DSA|RSA|SF)|SIG-[^/]+)");

    /**
     * <a href="https://docs.oracle.com/en/java/javase/11/docs/specs/jar/jar.html#signature-validation">Specification</a>
     */
    private static final Pattern SIGNATURE_PROPERTY_PATTERN = Pattern.compile(".*-Digest");

    private final RelocatingRemapper remapper;
    private final JarOutputStream jarOut;
    private final JarFile jarIn;

    private final Set<String> resources = new HashSet<>();

    JarRelocatorTask(RelocatingRemapper remapper, JarOutputStream jarOut, JarFile jarIn) {
        this.remapper = remapper;
        this.jarOut = jarOut;
        this.jarIn = jarIn;
    }

    void processEntries() throws IOException {
        for (Enumeration<JarEntry> entries = this.jarIn.entries(); entries.hasMoreElements(); ) {
            JarEntry entry = entries.nextElement();

            // The 'INDEX.LIST' file is an optional file, containing information about the packages
            // defined in a jar. Instead of relocating the entries in it, we delete it, since it is
            // optional anyway.
            //
            // We don't process directory entries, and instead opt to recreate them when adding
            // classes/resources.
            String name = entry.getName();
            if (name.equals("META-INF/INDEX.LIST") || entry.isDirectory()) {
                continue;
            }

            // Signatures will become invalid after remapping, so we delete them to avoid making the output useless
            if (SIGNATURE_FILE_PATTERN.matcher(name).matches()) {
                continue;
            }

            try (InputStream entryIn = this.jarIn.getInputStream(entry)) {
                processEntry(entry, entryIn);
            }
        }
    }

    private void processEntry(JarEntry entry, InputStream entryIn) throws IOException {
        String name = entry.getName();
        String mappedName = this.remapper.map(name);

        // ensure the parent directory structure exists for the entry.
        // 翻译：确保条目的父目录结构存在。
        processDirectory(mappedName, true);

        if (name.endsWith(".class")) {
            processClass(name, entryIn);
        } else if (name.equals("META-INF/MANIFEST.MF")) {
            processManifest(name, entryIn, entry.getTime());
        } else if (name.startsWith("META-INF/services/")) {
            processService(name, entryIn, entry.getTime());
        } else if (!this.resources.contains(mappedName)) {
            processResource(mappedName, entryIn, entry.getTime());
        }

        // 克隆一份 kotlin_builtins 文件在原来的位置
        if (name.endsWith(".kotlin_builtins")) {
            processResource(name, entryIn, entry.getTime());
        }
    }

    private void processDirectory(String name, boolean parentsOnly) throws IOException {
        int index = name.lastIndexOf('/');
        if (index != -1) {
            String parentDirectory = name.substring(0, index);
            if (!this.resources.contains(parentDirectory)) {
                processDirectory(parentDirectory, false);
            }
        }

        if (parentsOnly) {
            return;
        }

        // directory entries must end in "/"
        JarEntry entry = new JarEntry(name + "/");
        this.jarOut.putNextEntry(entry);
        this.resources.add(name);
    }

    private void processManifest(String name, InputStream entryIn, long lastModified) throws IOException {
        Manifest in = new Manifest(entryIn);
        Manifest out = new Manifest();

        out.getMainAttributes().putAll(in.getMainAttributes());

        for (Map.Entry<String, Attributes> entry : in.getEntries().entrySet()) {
            Attributes outAttributes = new Attributes();
            for (Map.Entry<Object, Object> property : entry.getValue().entrySet()) {
                String key = property.getKey().toString();
                if (!SIGNATURE_PROPERTY_PATTERN.matcher(key).matches()) {
                    outAttributes.put(property.getKey(), property.getValue());
                }
            }
            out.getEntries().put(entry.getKey(), outAttributes);
        }

        JarEntry jarEntry = new JarEntry(name);
        jarEntry.setTime(lastModified);
        this.jarOut.putNextEntry(jarEntry);

        out.write(this.jarOut);

        this.resources.add(name);
    }

    private void processService(String name, InputStream entryIn, long lastModified) throws IOException {
        // 对服务文件名和内容进行重定位
        String realName = name.substring("META-INF/services/".length()).replace('.', '/');
        String mappedName = "META-INF/services/" + this.remapper.map(realName).replace('/', '.');

        JarEntry jarEntry = new JarEntry(mappedName);
        jarEntry.setTime(lastModified);

        this.jarOut.putNextEntry(jarEntry);

        // 读取内容并进行重定位
        List<String> arr = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(entryIn, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            arr.add(this.remapper.map(line.replace('.', '/')).replace('/', '.'));
        }
        // 写入到 jar 文件中
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(this.jarOut, StandardCharsets.UTF_8));
        for (String l : arr) {
            writer.write(l);
            writer.newLine();
        }
        writer.flush();

        this.resources.add(mappedName);
    }

    private void processResource(String name, InputStream entryIn, long lastModified) throws IOException {
        JarEntry jarEntry = new JarEntry(name);
        jarEntry.setTime(lastModified);

        this.jarOut.putNextEntry(jarEntry);
        copy(entryIn, this.jarOut);

        this.resources.add(name);
    }

    private void processClass(String name, InputStream entryIn) throws IOException {
        ClassReader classReader = new ClassReader(entryIn);
        ClassWriter classWriter = new ClassWriter(0);
        RelocatingClassVisitor classVisitor = new RelocatingClassVisitor(classWriter, this.remapper, name);

        try {
            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES);
        } catch (Throwable e) {
            throw new RuntimeException("Error processing class " + name, e);
        }

        byte[] renamedClass = classWriter.toByteArray();

        // Need to take the .class off for remapping evaluation
        String mappedName = this.remapper.map(name.substring(0, name.indexOf('.')));

        // Now we put it back on so the class file is written out with the right extension.
        this.jarOut.putNextEntry(new JarEntry(mappedName + ".class"));
        this.jarOut.write(renamedClass);
    }

    private static void copy(InputStream from, OutputStream to) throws IOException {
        byte[] buf = new byte[8192];
        while (true) {
            int n = from.read(buf);
            if (n == -1) {
                break;
            }
            to.write(buf, 0, n);
        }
    }

    private static byte[] readFully(InputStream inputStream) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int len;
        while ((len = inputStream.read(buf)) > 0) {
            stream.write(buf, 0, len);
        }
        return stream.toByteArray();
    }
}
