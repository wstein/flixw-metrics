package dev.flixw.metrics;

import dev.flixw.metrics.adapter.Flix0761Adapter;
import dev.flixw.metrics.sdk.CompilerModel.Model;

import java.nio.file.Path;
import java.util.List;

/** Emits a normal JSON report for one compiler-embedded standard-library source. */
public final class StdlibCalibration {
    private StdlibCalibration() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3)
            throw new IllegalArgumentException(
                "usage: StdlibCalibration <compilable-project> <source-file> <virtual-path>");
        Path project = Path.of(args[0]).toAbsolutePath().normalize();
        Path source = Path.of(args[1]).toAbsolutePath().normalize();
        MetricsConfig config = MetricsConfig.defaults();
        Model model = new Flix0761Adapter().measureCompilerSource(project, args[2]);
        SourceMetrics text = SourceMetrics.measure(source.getParent(), List.of(source), config);
        Metrics.Report report = Metrics.of(1, model, text, config);
        System.out.print(report.render(Metrics.Format.JSON, null, config));
    }
}
