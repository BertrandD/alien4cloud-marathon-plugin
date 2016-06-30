package alien4cloud.plugin.marathon.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang.NotImplementedException;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import alien4cloud.model.components.*;
import alien4cloud.model.topology.NodeTemplate;
import alien4cloud.model.topology.RelationshipTemplate;
import alien4cloud.paas.exception.NotSupportedException;
import alien4cloud.paas.function.FunctionEvaluator;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSRelationshipTemplate;
import alien4cloud.paas.model.PaaSTopology;
import alien4cloud.paas.model.PaaSTopologyDeploymentContext;
import lombok.extern.log4j.Log4j;
import mesosphere.marathon.client.model.v2.*;

/**
 * @author Adrian Fraisse
 */
@Service
@Log4j
public class MarathonMappingService {

    // TODO: Store increments in DB, or retrieve from Marathon ?
    private AtomicInteger servicePortIncrement = new AtomicInteger(10000);

    private Map<String, Integer> mapPortEndpoints = Maps.newHashMap();

    /**
     * Parse an Alien deployment context into a Marathon group definition.
     * @param paaSTopologyDeploymentContext
     * @return
     */
    public Group buildGroupDefinition(PaaSTopologyDeploymentContext paaSTopologyDeploymentContext) {
        // Setup parent group
        Group parentGrp = new Group();
        // Group id == pass topology deployment id
        parentGrp.setId(paaSTopologyDeploymentContext.getDeploymentPaaSId().toLowerCase());
        parentGrp.setApps(Lists.newArrayList());
        parentGrp.setDependencies(Lists.newArrayList());

        // Docker containers are non-natives
        final List<PaaSNodeTemplate> paaSNodeTemplates = paaSTopologyDeploymentContext.getPaaSTopology().getNonNatives();

        paaSNodeTemplates.forEach(node -> {
            parentGrp.getApps().add(buildAppDefinition(node, paaSTopologyDeploymentContext.getPaaSTopology()));
        });

        return parentGrp;
    }

    /**
     * Parse an alien topology into a Marathon App Definition
     *
     */
    public App buildAppDefinition(PaaSNodeTemplate paaSNodeTemplate, PaaSTopology paaSTopology) {

        final NodeTemplate nodeTemplate = paaSNodeTemplate.getTemplate();
        final Map<String, AbstractPropertyValue> nodeTemplateProperties = nodeTemplate.getProperties();

        // Generate app structure
        App appDef = new App();
        appDef.setInstances(1); // Todo get scalingPolicy
        appDef.setId(paaSNodeTemplate.getId().toLowerCase());
        Container container = new Container();
        Docker docker = new Docker();
        container.setType("DOCKER");
        container.setDocker(docker);
        appDef.setContainer(container);
        docker.setPortMappings(Lists.newArrayList());
        docker.setParameters(Lists.newArrayList());
        appDef.setEnv(Maps.newHashMap());
        appDef.setLabels(Maps.newHashMap());
        appDef.setDependencies(Lists.newArrayList());

        // Resources TODO: check null
        final ScalarPropertyValue cpu_share = (ScalarPropertyValue) nodeTemplateProperties.get("cpu_share");
        appDef.setCpus(Double.valueOf(cpu_share.getValue()));

        final ScalarPropertyValue mem_share = (ScalarPropertyValue) nodeTemplateProperties.get("mem_share");
        appDef.setMem(Double.valueOf(mem_share.getValue()));

        // Only the create operation is supported
        final Operation createOperation = paaSNodeTemplate.getInterfaces()
                .get("tosca.interfaces.node.lifecycle.Standard").getOperations()
                .get("create");

        // Retrieve docker image
        final ImplementationArtifact implementationArtifact = createOperation.getImplementationArtifact();
        if (implementationArtifact != null) {
            final String artifactRef = implementationArtifact.getArtifactRef();
            if (artifactRef.endsWith(".dockerimg")) docker.setImage(artifactRef.split(Pattern.quote(".dockerimg"))[0]); // TODO use a regex instead
            else throw new NotSupportedException("Create artifact should be in the form <hub/repo/image:version.dockerimg>");
        } else throw new NotImplementedException("Create artifact should contain the image");

        // Handle capabilities
        Map<String, Integer> endpoints = Maps.newHashMap();
        // todo : YAY java 8 !
        nodeTemplate.getTemplate().getCapabilities().forEach((name, capability) -> {
            if (capability.getType().contains("capabilities.endpoint")) { // FIX ME : better check of capability types...
                // Retrieve port mapping for the capability - note : if no port is specified then let marathon decide.
                Port port = capability.getProperties().get("port") != null ?
                        new Port(Integer.valueOf(((ScalarPropertyValue) capability.getProperties().get("port")).getValue())) :
                        new Port(0);

                // FIXME: Attribute service port only if necessary - check relationships templates
                // Si pas déjà fait lors du mapping d'une source, on alloue un port de service
                final Integer servicePort = mapPortEndpoints.getOrDefault(paaSNodeTemplate.getId().concat(name), this.servicePortIncrement.getAndIncrement());
                port.setServicePort(servicePort);
                mapPortEndpoints.put(paaSNodeTemplate.getId().concat(name), servicePort);

                // FIXME: set haproxy_group only if necessary
                appDef.getLabels().put("HAPROXY_GROUP", "internal");

                if (capability.getProperties().containsKey("docker_bridge_port_mapping")) {
                    docker.setNetwork("BRIDGE");
                    final Integer hostPort = Integer.valueOf(((ScalarPropertyValue) capability.getProperties().get("docker_bridge_port_mapping")).getValue());
                    port.setHostPort(hostPort);
                    port.setProtocol("tcp");
                } else
                    docker.setNetwork("HOST");

                docker.getPortMappings().add(port);
            }
        });
        // une seule map avec key: <node_name><capability_name>


        // Get connectsTo relationships - only those are supported. I
        // TODO : Get Requirement target properties - WARN: relationships can be null (apparently).
        if (nodeTemplate.getRelationships() != null) {
            nodeTemplate.getRelationships().forEach((k, v) -> {
                if (v.getType().equalsIgnoreCase("tosca.relationships.connectsto")) { // TODO: verif si target est bien de type docker
                    if (!mapPortEndpoints.containsKey(v.getTarget().concat(v.getTargetedCapabilityName()))) {
                        // Si la target n'a pas déjà été parsée, on pré-alloue un service port pour permettre le mapping
                        mapPortEndpoints.put(v.getTarget().concat(v.getTargetedCapabilityName()), this.servicePortIncrement.getAndIncrement());
                    }
                    // Anyway, add a dependency to the target
                    appDef.getDependencies().add(v.getTarget().toLowerCase());
                }
            });
        }

        // Resources
        final ScalarPropertyValue cpu_share = (ScalarPropertyValue) nodeTemplate.getTemplate().getProperties().get("cpu_share");
        appDef.setCpus(Double.valueOf(cpu_share.getValue()));

        final ScalarPropertyValue mem_share = (ScalarPropertyValue) nodeTemplate.getTemplate().getProperties().get("mem_share");
        appDef.setMem(Double.valueOf(mem_share.getValue()));

        return appDef;
    }
}
