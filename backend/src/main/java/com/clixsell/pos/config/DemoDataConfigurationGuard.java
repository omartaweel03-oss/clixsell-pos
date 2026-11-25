package com.clixsell.pos.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The demo data (stores, Store Admins and Cashiers with the PUBLISHED demo passwords) may only be
 * created together with the demo-password exception of the {@code demo} profile
 * ({@code clixsell.system-admin.allow-demo-password=true}). {@code clixsell.demo-data=true} alone would
 * otherwise start creating demo stores and then be refused half-way by the password rules.
 *
 * <p>This check runs while Spring reads its configuration — before the DataSource and Flyway exist —
 * so a refused start opens no database, runs no migration and creates no file or record. The message
 * names the settings only (no password).
 */
@Component
public class DemoDataConfigurationGuard implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        String problem = problem(
                environment.getProperty("clixsell.demo-data", Boolean.class, false),
                environment.getProperty("clixsell.system-admin.allow-demo-password", Boolean.class, false));
        if (problem != null) throw new IllegalStateException(problem);
    }

    /** Why this demo-data configuration is refused, or null when it is fine. */
    public static String problem(boolean demoData, boolean allowDemoPasswords) {
        if (demoData && !allowDemoPasswords) {
            return "clixsell.demo-data=true needs the 'demo' profile (--spring.profiles.active=demo): the demo"
                    + " accounts use the published demo passwords, which are refused outside that profile."
                    + " Nothing was created: no database was opened and no demo store or user was added.";
        }
        return null;
    }
}
