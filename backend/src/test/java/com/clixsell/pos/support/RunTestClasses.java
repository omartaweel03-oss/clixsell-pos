package com.clixsell.pos.support;

import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Runs the given test classes in THIS (separate) JVM and exits with 0 only when
 * every test was found and passed. Used by {@code BackupPreservationTest} to run
 * the backup-creating tests in their own folder and process.
 */
public final class RunTestClasses {

    private RunTestClasses() {
    }

    public static void main(String[] classNames) {
        LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request();
        for (String name : classNames) request.selectors(selectClass(name));
        LauncherDiscoveryRequest built = request.build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(built, listener);
        TestExecutionSummary summary = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out, true);
        summary.printTo(out);
        summary.printFailuresTo(out, 25);
        out.flush();
        boolean ok = summary.getTestsFoundCount() > 0 && summary.getTotalFailureCount() == 0;
        System.exit(ok ? 0 : 1);
    }
}
