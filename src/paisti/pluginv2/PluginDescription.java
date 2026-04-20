package paisti.pluginv2;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface PluginDescription {
	String name();

	/**
	 * Internal name used in the config.
	 */
	String configName() default "";

	/**
	 * A short, one-line summary of the plugin.
	 */
	String description() default "";

	/**
	 * If this plugin should be defaulted to on. Plugin-Hub plugins should always
	 * have this set to true (the default), since having them off by defaults means
	 * the user has to install the plugin, then separately enable it, which is confusing.
	 */
	boolean enabledByDefault() default true;

	/**
	 * Whether plugin is hidden from configuration panel
	 */
	boolean hidden() default false;
}
