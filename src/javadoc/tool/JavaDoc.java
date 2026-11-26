/*
 * Generate JavaDoc for Processing Sketch.
 *
 * ##copyright##
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General
 * Public License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 * Boston, MA  02111-1307  USA
 *
 * @author   ##author##
 * @modified ##date##
 * @version  ##tool.prettyVersion##
 */

package javadoc.tool;

import processing.app.Base;
import processing.app.Platform;
import processing.app.Sketch;
import processing.app.SketchCode;
import processing.app.Preferences;
import processing.app.tools.Tool;
import processing.app.ui.Editor;
import java.io.File;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public class JavaDoc implements Tool {
  Base base;

  // comments, text blocks, strings and characters must not be changed
  private static final Pattern PROTECTED = Pattern.compile(
    "//[^\\n]*+"
    + "|/\\*(?s:.*?)\\*/"
    + "|\"\"\"(?s:.*?)\"\"\""
    + "|\"(?:[^\"\\\\\\n]++|\\\\.)*+\""
    + "|'(?:[^'\\\\\\n]++|\\\\.)*+'");


  // decimal numbers without suffix, which are of type float in Processing
  private static final String DECIMAL =
    "(?<![\\w.])((?:\\d+\\.\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?|\\d+[eE][+-]?\\d+)(?![\\w.])";

  public String getMenuTitle() {
    return "generate JavaDoc";
  }

  public void init(Base base) {
    // Store a reference to the Processing application itself
    this.base = base;
  }

  public static List<String> findFiles(Path path, String fileExtension) throws IOException {

    if (!Files.isDirectory(path)) {
      return Collections.emptyList();
    }

    List<String> result = new ArrayList<>();

    Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        String name = file.toAbsolutePath().toString();
        // this only test if pathname ends with a certain extension
        if (!Files.isDirectory(file) && name.toLowerCase().endsWith(fileExtension)) {
          result.add(name);
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException e) {
        // skip unreadable files and folders, but keep searching
        System.err.println("Skipping " + file + ": " + e);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException e) {
        if (e != null) {
          System.err.println("Incomplete search in " + dir + ": " + e);
        }
        return FileVisitResult.CONTINUE;
      }
    });

    return result;
  }

  private String getJarsInDir(File file) {
    return getJarsInDir(file.toPath());
  }

  private String getJarsInDir(Path path) {
    String extraLibs = "";
    boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
    try {
      List<String> files = findFiles(path, "jar");
      if (!files.isEmpty()) {
        extraLibs = (isWindows?";":":") + String.join(isWindows?";":":", files);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return extraLibs;
  }

  /** Translates Processing syntax to Java, but leaves comments and strings untouched. */
  private static String toJava(String code) {
    StringBuilder result = new StringBuilder();
    Matcher m = PROTECTED.matcher(code);
    int end = 0;
    while (m.find()) {
      result.append(translate(code.substring(end, m.start())));
      result.append(m.group());
      end = m.end();
    }
    result.append(translate(code.substring(end)));
    return result.toString();
  }

  private static String translate(String code) {
    return code.replace("#", "0x")
      .replaceAll(DECIMAL, "$1f")
      .replaceAll("\\bint\\b\\s*[(]", "PApplet.parseInt(")
      .replaceAll("\\bfloat\\b\\s*[(]", "PApplet.parseFloat(")
      .replaceAll("\\bboolean\\b\\s*[(]", "PApplet.parseBoolean(")
      .replaceAll("\\bbyte\\b\\s*[(]", "PApplet.parseByte(")
      .replaceAll("\\bchar\\b\\s*[(]", "PApplet.parseChar(")
      // the type color becomes int, but calls of color() remain
      .replaceAll("\\bcolor\\b(?!\\s*[(])", "int");
  }

  /** Deletes the pages created by javadoc. Other files remain untouched. */
  private static void deleteGeneratedPages(Path folder) throws IOException {
    for (String name : findFiles(folder, ".html")) {
      Path file = Paths.get(name);
      // ISO 8859-1 can read any file, and the marker contains only ASCII characters
      String page = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
      if (page.contains("<!-- Generated by javadoc")) {
        Files.delete(file);
      }
    }
  }

  public void run() {
    // Get the currently active Editor to run the Tool on it
    Editor editor = base.getActiveEditor();

    // run in the background, so that the Processing UI does not freeze
    new Thread(() -> generate(editor), "JavaDoc-Generator").start();
  }

  private void generate(Editor editor) {
    boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");

    // Fill in author.name, author.url, tool.prettyVersion and
    // project.prettyName in build.properties for them to be auto-replaced here.
    //System.out.println("JavaDoc Tool. ##tool.name## ##tool.prettyVersion## by ##author##");
    Sketch sketch = editor.getSketch();
    System.out.println("Generating JavaDoc for Sketch \""+sketch.getName()+"\"");


    File folder = sketch.getFolder();
    String extraLibs = getJarsInDir(Paths.get(Preferences.getSketchbookPath()+(isWindows?'\\':'/')+"libraries"));
    extraLibs += getJarsInDir(folder);
    extraLibs += getJarsInDir(Platform.getContentFile("modes/java/libraries"));
    SketchCode[] codes = sketch.getCode();
    String mainTab = codes[0].getProgram();
    if (!mainTab.contains("setup") && !mainTab.contains("draw")) {
      System.err.println("Can only generate JavaDoc for sketches in dynamic mode.");
      return;
    }
    Path argFile = null;
    try {
      File ref = new File(folder, "reference");
      ref.mkdir();

      StringBuilder main = new StringBuilder();
      StringBuilder imports = new StringBuilder();
      List<String> javaFiles = new ArrayList<>();

      for (SketchCode c : codes) {
        if (c.getExtension().equals("java")) {
          javaFiles.add(c.getFile().getAbsolutePath());
        } else {
          String tab = c.getProgram();
          boolean hasImports = tab.indexOf("import")>=0;
          if (hasImports) {
            String[] code = tab.split("\n");
            boolean blockComment = false;
            for (String line : code) {
              if (blockComment) {
                int stopComment = line.indexOf("*/");
                if (stopComment >= 0) {
                  int startComment = line.indexOf("/*");
                    if (startComment < stopComment) {
                      blockComment = false;
                    }
                }
              } else {
                int lineComment = line.indexOf("//");
                int startComment = line.indexOf("/*");
                if (startComment >= 0 && (lineComment < 0 || lineComment > startComment)) {
                  int stopComment = line.indexOf("*/");
                  if (startComment > stopComment) {
                    blockComment = true;
                  }
                }
              }
              if (!blockComment && line.trim().startsWith("import")) {
                imports.append(line);
                imports.append("\n");
              } else {
                main.append(line);
                main.append("\n");
              }
            }
          } else {
            main.append(tab);
            // the next tab must not continue the last line, which may be a comment
            main.append("\n");
          }
        }
      }

      File src = new File(ref, sketch.getName()+".java");
      Writer myWriter = Files.newBufferedWriter(src.toPath(), StandardCharsets.UTF_8);
      // first all imports
      myWriter.write(imports.toString());
      myWriter.write("\n");
      // then Sketch main class
      myWriter.write("import processing.core.*;\nimport processing.data.*;\nimport processing.event.*;\nimport processing.opengl.*;\nimport static processing.core.PConstants.*;\nimport java.util.HashMap;\nimport java.util.ArrayList;\nimport java.io.File;\nimport java.io.BufferedReader;\nimport java.io.PrintWriter;\nimport java.io.InputStream;\nimport java.io.OutputStream;\nimport java.io.IOException;\n\n");

      myWriter.write("/** Processing Sketch "+sketch.getName()+" */\npublic class "+sketch.getName()+" {\n");
      myWriter.write(toJava(main.toString()));
      myWriter.write("\n}\n\n");
      myWriter.close();

      List<String> command = new ArrayList<>();
      command.add("-encoding");
      command.add("UTF-8");
      command.add(src.getAbsolutePath());
      for (String j : javaFiles) {
        command.add(j);
      }
      command.add("-d");
      command.add(ref.getAbsolutePath());
      command.add("-package");
      command.add("-quiet");
      command.add("-Xdoclint:all,-accessibility");
      command.add("-cp");
      command.add(System.getProperty("java.class.path") + extraLibs);

      // pass all arguments in a file, because the Windows command line is limited in length
      argFile = Files.createTempFile("javadoc", ".args");
      List<String> args = command.stream()
        .map(a -> "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .collect(Collectors.toList());
      Files.write(argFile, args, StandardCharsets.UTF_8);

      // remove the pages of earlier runs, for example of classes which no longer exist
      deleteGeneratedPages(ref.toPath());
      File index = new File(ref, "index.html");

      ProcessBuilder builder = new ProcessBuilder(
        Platform.getJavaHome() + (isWindows?"\\bin\\javadoc":"/bin/javadoc"),
        // use UTF-8 for the argument file and the output, independent of the Java version
        "-J-Dfile.encoding=UTF-8",
        "-J-Dstdout.encoding=UTF-8",
        "-J-Dstderr.encoding=UTF-8",
        "@" + argFile.toAbsolutePath());
      // merge stderr into stdout, so that no pipe buffer can fill up unread
      builder.redirectErrorStream(true);
      Process process = builder.start();
      process.getOutputStream().close();

      try (BufferedReader reader = new BufferedReader(
             new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          System.out.println(line);
        }
      }
      int exitCode = process.waitFor();

      // javadoc may report errors and still create the documentation
      if (index.exists()) {
        if (exitCode != 0) {
          System.err.println("JavaDoc reported errors, the documentation may be incomplete.");
        }
        Platform.openURL("file:///" + index.getAbsolutePath().replace("\\","/"));
      } else {
        System.err.println("JavaDoc failed (exit code " + exitCode + ").");
      }
    }
    catch (IOException e) {
      System.err.println("JavaDoc failed: " + e.getMessage());
      e.printStackTrace();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    finally {
      if (argFile != null) {
        argFile.toFile().delete();
      }
    }
  }
}
