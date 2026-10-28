// 逐函数导出 Ghidra C 伪代码，并记录不可反编译的函数。
// @category 111.SourceRecovery
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ExportDecompiledC extends GhidraScript {
    private static String tsv(String value) {
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 1) {
            throw new IllegalArgumentException("ExportDecompiledC.java needs an output directory");
        }
        Path out = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(out);
        int total = 0, decompiled = 0, failed = 0;
        DecompInterface engine = new DecompInterface();
        engine.toggleCCode(true);
        engine.toggleSyntaxTree(false);
        engine.setSimplificationStyle("decompile");
        if (!engine.openProgram(currentProgram)) {
            throw new IOException("Ghidra cannot open the program for decompilation");
        }
        try (PrintWriter c = new PrintWriter(Files.newBufferedWriter(out.resolve("decompiled.c"), StandardCharsets.UTF_8));
             PrintWriter records = new PrintWriter(Files.newBufferedWriter(out.resolve("functions.tsv"), StandardCharsets.UTF_8))) {
            c.println("/* Ghidra generated C pseudocode. Not original source or directly compilable code. */");
            c.println("/* Program: " + currentProgram.getName().replace("*/", "* /") + " */");
            records.println("address\tname\tstatus\terror");
            for (Function fn : currentProgram.getFunctionManager().getFunctions(true)) {
                if (monitor.isCancelled()) throw new IOException("Analysis cancelled; output incomplete");
                if (fn.isExternal()) continue;
                total++;
                String address = fn.getEntryPoint().toString();
                String name = fn.getName();
                try {
                    DecompileResults result = engine.decompileFunction(fn, 120, monitor);
                    if (result.decompileCompleted() && result.getDecompiledFunction() != null) {
                        String code = result.getDecompiledFunction().getC();
                        if (code == null || code.isBlank()) throw new IOException("Empty decompiled C");
                        c.println("\n/* " + address + " : " + name.replace("*/", "* /") + " */");
                        c.println(code);
                        records.println(tsv(address) + "\t" + tsv(name) + "\tok\t");
                        decompiled++;
                    } else {
                        failed++;
                        String error = result.getErrorMessage();
                        records.println(tsv(address) + "\t" + tsv(name) + "\tfailed\t" + tsv(error == null ? "unknown" : error));
                    }
                } catch (Exception e) {
                    failed++;
                    records.println(tsv(address) + "\t" + tsv(name) + "\tfailed\t" + tsv(e.toString()));
                }
            }
        } finally {
            engine.dispose();
        }
        String summary = "{\n"
                + "  \"tool\": \"Ghidra\",\n"
                + "  \"program_name\": \"" + json(currentProgram.getName()) + "\",\n"
                + "  \"total_functions\": " + total + ",\n"
                + "  \"decompiled_functions\": " + decompiled + ",\n"
                + "  \"failed_functions\": " + failed + "\n"
                + "}\n";
        Files.writeString(out.resolve("summary.json"), summary, StandardCharsets.UTF_8);
        if (decompiled == 0) throw new IOException("No functions were decompiled");
        println("111 Ghidra: " + decompiled + "/" + total + " functions exported to " + out);
    }
}
