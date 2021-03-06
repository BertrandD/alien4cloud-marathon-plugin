package alien4cloud.plugin.marathon.location;

import alien4cloud.model.deployment.matching.MatchingConfiguration;
import alien4cloud.model.orchestrators.locations.LocationResourceTemplate;
import alien4cloud.orchestrators.plugin.ILocationConfiguratorPlugin;
import alien4cloud.orchestrators.plugin.ILocationResourceAccessor;
import alien4cloud.orchestrators.plugin.model.PluginArchive;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Adrian Fraisse
 */
@Component
@Scope("prototype")
public class MarathonLocationConfiguratorFactory {

    @Inject
    private ApplicationContext applicationContext;

    public ILocationConfiguratorPlugin newInstance(String locationType) {
        if ("marathon".equals(locationType)) {
            MarathonLocationConfigurator configurator = applicationContext.getBean(MarathonLocationConfigurator.class);
            return configurator;
        }
        return new ILocationConfiguratorPlugin() {
            @Override
            public List<PluginArchive> pluginArchives() {
                return new ArrayList<>();
            }

            @Override
            public List<String> getResourcesTypes() {
                return new ArrayList<>();
            }

            @Override
            public Map<String, MatchingConfiguration> getMatchingConfigurations() {
                return new HashMap<>();
            }

            @Override
            public List<LocationResourceTemplate> instances(ILocationResourceAccessor resourceAccessor) {
                return null;
            }


        };
    }
}
