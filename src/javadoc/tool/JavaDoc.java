/**
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.FileWriter;
import java.lang.StringBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;


// when creating a tool, the name of the main class which implements Tool must
// be the same as the value defined for project.name in your build.properties

public class JavaDoc implements Tool {
  Base base;


  public String getMenuTitle() {
    return "generate JavaDoc";
  }


  public void init(Base base) {
    // Store a reference to the Processing application itself
    this.base = base;
  }

  public static List<String> findFiles(Path path, String fileExtension) throws IOException {

    if (!Files.isDirectory(path)) {
        throw new IllegalArgumentException("Path must be a directory!");
    }

    List<String> result;

    try (Stream<Path> walk = Files.walk(path)) {
        result = walk
                .filter(p -> !Files.isDirectory(p))
                // this is a path, not string,
                // convert path to string first
                .map(p -> p.toString().toLowerCase())
                // this only test if pathname ends with a certain extension
                .filter(f -> f.endsWith(fileExtension))
                .collect(Collectors.toList());
    }

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
        extraLibs = isWindows?";":":" + String.join(isWindows?";":":", files);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return extraLibs;
  }

  public void run() {
    boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");

    // Get the currently active Editor to run the Tool on it
    Editor editor = base.getActiveEditor();

    // Fill in author.name, author.url, tool.prettyVersion and
    // project.prettyName in build.properties for them to be auto-replaced here.
    //System.out.println("JavaDoc Tool. ##tool.name## ##tool.prettyVersion## by ##author##");
    Sketch sketch = editor.getSketch();
    System.out.println("Generating JavaDoc for Sketch \""+sketch.getName()+"\"");


    File folder = sketch.getFolder();
    String extraLibs = getJarsInDir(Paths.get(Preferences.getSketchbookPath()+(isWindows?'\\':'/')+"libraries"));
    extraLibs += getJarsInDir(folder);
    extraLibs += getJarsInDir(processing.app.Platform.getContentFile("modes/java/libraries"));
    SketchCode codes[] = sketch.getCode();
    String mainTab = codes[0].getProgram();
    if (!mainTab.contains("setup") && !mainTab.contains("draw")) {
        System.err.println("Can only generate JavaDoc for sketches in dynamic mode.");
        return;
    }
    try {
      File ref = new File(folder, "reference");
      ref.mkdir();

      StringBuilder main = new StringBuilder();
      StringBuilder imports = new StringBuilder();

      for (SketchCode c : codes) {
        String tab = c.getProgram();
        boolean hasImports = tab.indexOf("import")>=0;
        if (hasImports) {
          String[] code = tab.split("\n");
          boolean blockComment = false;
          for (String line : code) {
            if (blockComment) {
                int lineComment = line.indexOf("//");
                int stopComment = line.indexOf("*/");
                if (stopComment>=0) {
                    int startComment = line.indexOf("/*");
                    if (startComment<stopComment) {
                        blockComment = false;
                    }
                }
            } else {
                int lineComment = line.indexOf("//");
                int startComment = line.indexOf("/*");
                if (startComment>=0 && (lineComment<0 || lineComment>startComment)) {
                    int stopComment = line.indexOf("*/");
                    if (startComment>stopComment) {
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
        }
      }

      File src = new File(ref, sketch.getName()+".java");
      FileWriter myWriter = new FileWriter(src);
      // first all imports
      myWriter.write(imports.toString());
      myWriter.write("\n");
      // then Sketch main class
      myWriter.write("import processing.core.*;\nimport processing.data.*;\nimport processing.event.*;\nimport processing.opengl.*;\nimport java.util.HashMap;\nimport java.util.ArrayList;\nimport java.io.File;\nimport java.io.BufferedReader;\nimport java.io.PrintWriter;\nimport java.io.InputStream;\nimport java.io.OutputStream;\nimport java.io.IOException;\n\n");

      myWriter.write("/** "+sketch.getName()+" */\npublic class "+sketch.getName()+" {\n");
      myWriter.write(main.toString().replace("#", "0x").replaceAll("\\bint\\b\\s*[(]", "PApplet.parseInt(").replaceAll("\\bfloat\\b\\s*[(]", "PApplet.parseFloat(").replaceAll("\\bboolean\\b\\s*[(]", "PApplet.parseBoolean(").replaceAll("\\bbyte\\b\\s*[(]", "PApplet.parseByte(").replaceAll("\\bchar\\b\\s*[(]", "PApplet.parseChar(").replaceAll("\\b(color)\\b\\s*([^\\(])", "int $2"));
      myWriter.write("\n}\n\n");
      myWriter.close();

      ProcessBuilder builder = new ProcessBuilder(
          System.getProperty("java.home") + (isWindows?"\\bin\\javadoc":"/bin/javadoc"),
          src.getAbsolutePath(),
          "-d", ref.getAbsolutePath(),
          "-package", "-quiet",
          "-cp", System.getProperty("java.class.path") + extraLibs
          );
      Process process = builder.start();

      String lines;
      BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      while ((lines = reader.readLine()) != null) {
        System.out.println(lines);
      }
      reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
      while ((lines = reader.readLine()) != null) {
        System.err.println(lines);
      }

      File index = new File(ref, "index.html");
      if (index.exists()) {
        Platform.openURL("file:///" + index.getAbsolutePath().replace("\\","/"));
      } else {
        System.err.println("JavaDoc failed.");
      }
    }
    catch (IOException e) {
    }
  }
}
