package com.clixsell.pos.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D5: demo data only together with the demo profile's demo-password exception. */
class DemoDataConfigurationGuardTest {

    private void start(String demoData, String allow) {
        MockEnvironment env = new MockEnvironment();
        if (demoData != null) env.setProperty("clixsell.demo-data", demoData);
        if (allow != null) env.setProperty("clixsell.system-admin.allow-demo-password", allow);
        DemoDataConfigurationGuard guard = new DemoDataConfigurationGuard();
        guard.setEnvironment(env);
        guard.postProcessBeanFactory(null);
    }

    @Test
    void demoDataWithoutTheDemoProfileIsRefusedClearly() {
        assertThatThrownBy(() -> start("true", null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs the 'demo' profile").hasMessageContaining("Nothing was created")
                .hasMessageNotContaining("Pass-1").hasMessageNotContaining("Admin12345");
        assertThatThrownBy(() -> start("true", "false")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void normalStartAndTheDemoProfilePass() {
        start(null, null);              // normal installation
        start("false", null);
        start("true", "true");          // the demo profile (application-demo.properties)
        start(null, "true");
        assertThat(DemoDataConfigurationGuard.problem(false, false)).isNull();
    }
}
