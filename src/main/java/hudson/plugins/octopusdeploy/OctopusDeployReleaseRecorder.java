package hudson.plugins.octopusdeploy;
import com.octopusdeploy.api.*;
import hudson.*;
import hudson.model.*;
import jenkins.model.*;
import hudson.tasks.*;
import hudson.scm.*;
import hudson.util.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import net.sf.json.*;
import org.kohsuke.stapler.*;
import org.kohsuke.stapler.export.*;

/**
 * Creates releases
 */
public class OctopusDeployReleaseRecorder extends Recorder implements Serializable {
    /**
     * The project name as defined in Octopus.
     */
    private final String project;
    public String getProject() {
        return project;
    }

    /**
     * The release version as defined in Octopus.
     */
    private final String releaseVersion;
    public String getReleaseVersion() {
        return releaseVersion;
    }


    /**
     * Is there release notes for this release?
     */
    private final boolean releaseNotes;
    public boolean getReleaseNotes() {
        return releaseNotes;
    }
    
    /**
     * Where are the release notes located?
     */
    private final String releaseNotesSource;
    public String getReleaseNotesSource() {
        return releaseNotesSource;
    }

    /**
     * The file that the release notes are in.
     */
    private final String releaseNotesFile;
    public String getReleaseNotesFile() {
        return releaseNotesFile;
    }
    
    /**
     * Should this release be deployed immediately?
     */
    private final boolean deployImmediately;
    public boolean getDeployImmediately() {
        return deployImmediately;
    }

    /**
     * The environment as defined in Octopus to deploy to.
     */
    private final String env;
    public String getEnv() {
        return env;
    }
    
    /**
     * All packages as defined in Octopus
     */
    private final List<PackageConfiguration> packageConfigs;
    @Exported
    public List<PackageConfiguration> getPackageConfigs() {
        return packageConfigs;
    }

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public OctopusDeployReleaseRecorder(String project, String releaseVersion, boolean releaseNotes, String releaseNotesSource, String releaseNotesFile, boolean deployImmediately, String env, List<PackageConfiguration> packageConfigs) {
        this.project = project;
        this.releaseVersion = releaseVersion;
        this.releaseNotes = releaseNotes;
        this.releaseNotesSource = releaseNotesSource;
        this.releaseNotesFile = releaseNotesFile;
        this.deployImmediately = deployImmediately;
        this.env = env;
        this.packageConfigs = packageConfigs;
    }
    
    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        boolean success = true;
        Log log = new Log(listener);
        log.info("Started Octopus Release");
        log.info("=======================");
        log.info("Project: " + project);
        log.info("Release Version: " + releaseVersion);
        log.info("Release Notes?: " + releaseNotes);
        log.info("Release Notes Source: " + releaseNotesSource);
        log.info("Release Notes File: " + releaseNotesFile);
        log.info("Deploy this Release Immediately?: " + deployImmediately);
        log.info("Environment: " + env);
        if (packageConfigs == null || packageConfigs.isEmpty()) {
            log.info("No package configurations");
        } else {
            log.info("Package Configurations:");
            for (PackageConfiguration pc : packageConfigs) {
                log.info("\t" + pc.getPackageName() + "\tv" + pc.getPackageVersion());
            }
        }
        log.info("=======================");
        ((DescriptorImpl)getDescriptor()).setGlobalConfiguration();
        OctopusApi api = new OctopusApi(((DescriptorImpl)getDescriptor()).octopusHost, ((DescriptorImpl)getDescriptor()).apiKey);
        
        com.octopusdeploy.api.Project p = null;
        try {
            p = api.getProjectByName(project);
        } catch (Exception ex) {
            log.fatal(String.format("Retrieving project name '%s' failed with message '%s'",
                    project, ex.getMessage()));
            success = false;
        }
        if (p == null) {
            log.fatal("Project was not found.");
            success = false;
        }

        // Check packageVersion
        String releaseNotesContent = null;
        if (releaseNotes) {
            if (releaseNotesSource.equals("file")) {
                releaseNotesContent = getReleaseNotesFromFile(build);
            } else if (releaseNotesSource.equals("scm")) {
                releaseNotesContent = getReleaseNotesFromScm(build);
            } else {
                log.fatal(String.format("Bad configuration: if using release notes, should have source of file or scm. Found '%s'", releaseNotesSource));
                success = false;
            }
        }
        
        com.octopusdeploy.api.Environment environment = null;
        if (deployImmediately) {
            try {
                environment = api.getEnvironmentByName(env);
            } catch (Exception ex) {
                log.fatal(String.format("Retrieving environment name '%s' failed with message '%s'",
                    environment, ex.getMessage()));
                success = false;
            }
            if (environment == null) {
                log.fatal("Environment was not found.");
                success = false;
            }
        }
        
        
        if (!success) { // Early exit
            return success;
        }
        
        // Check packageOverrides
        Set<SelectedPackage> selectedPackages = null;
        if (packageConfigs != null && !packageConfigs.isEmpty()) {
            selectedPackages = new HashSet<SelectedPackage>();
            for (PackageConfiguration pc : packageConfigs) {
                selectedPackages.add(new SelectedPackage(pc.getPackageName(), pc.getPackageVersion()));
            }
        }
        
        try {
            log.info(api.createRelease(p.getId(), releaseVersion, releaseNotesContent, selectedPackages));
        } catch (IOException ex) {
            log.fatal("Failed to create release: " + ex.getMessage());
            success = false;
        }
        
        if (success && deployImmediately) {
            try {
                api.executeDeployment(releaseVersion, p.getId());
            } catch (IOException ex) {
                log.fatal("Failed to deploy: " + ex.getMessage());
                success = false;
            }
        }
            
        return success;
    }
    
    private String getReleaseNotesFromFile(AbstractBuild build) {
        FilePath path = new FilePath(build.getWorkspace(), releaseNotesFile);
        //Files.readAllLines(path.to)
        return "need to read this";
    }
    
    private String getReleaseNotesFromScm(AbstractBuild build) {
        StringBuilder notes = new StringBuilder();
        AbstractProject project = build.getProject();
        AbstractBuild lastSuccessfulBuild = (AbstractBuild)project.getLastSuccessfulBuild();
        AbstractBuild currentBuild = null;
        if (lastSuccessfulBuild == null) {
            AbstractBuild lastBuild = (AbstractBuild)project.getLastBuild();
            currentBuild = lastBuild;
        }
        else
        {
            currentBuild = lastSuccessfulBuild.getNextBuild();
        }
        if (currentBuild != null) {
            while (currentBuild != build)
            {
                ChangeLogSet<? extends ChangeLogSet.Entry> changeSet = currentBuild.getChangeSet();
                for(Object item : changeSet.getItems())
                {
                    ChangeLogSet.Entry entry = (ChangeLogSet.Entry)item;
                    notes.append(entry.getMsg() + "\n");
                }
                currentBuild = currentBuild.getNextBuild();
            }
        }
        
        return notes.toString();
    }
    
    /**
     * Descriptor for {@link OctopusDeployReleaseRecorder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        private String octopusHost;
        private String apiKey;
        private boolean loadedConfig;
        
        public DescriptorImpl() {
            load();
        }
        
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "OctopusDeploy Release";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws Descriptor.FormException {
            save();
            return true;
        }
        
        /**
        * Loads the OctopusDeployPlugin descriptor and pulls configuration from it
        * for API Key, and Host.
        */
        private void setGlobalConfiguration() {
            // NOTE  - This method is not being called from the constructor due 
            // to a circular dependency issue on startup
            if (!loadedConfig) { 
                OctopusDeployPlugin.DescriptorImpl descriptor = (OctopusDeployPlugin.DescriptorImpl) 
                       Jenkins.getInstance().getDescriptor(OctopusDeployPlugin.class );
                apiKey = descriptor.getApiKey();
                octopusHost = descriptor.getOctopusHost();
                loadedConfig = true;
            }
        }
        
        /**
         * Check that the project field is not empty.
         * @param project The name of the project.
         * @return Ok if not empty, error otherwise.
         * @throws java.io.IOException
         * @throws javax.servlet.ServletException
         */
        public FormValidation doCheckProject(@QueryParameter String project) throws IOException, ServletException {
            // TODO: Extract this to be shared between plugins
            setGlobalConfiguration(); 
            project = project.trim(); // TODO: Extract this to be shared between plugins
            if (project.isEmpty()) {
                return FormValidation.error("Please provide a project name.");
            }
            OctopusApi api = new OctopusApi(octopusHost, apiKey);
            try {
                com.octopusdeploy.api.Project p = api.getProjectByName(project, true);
                if (p == null)
                {
                    return FormValidation.error("Project not found.");
                }
                if (!project.equals(p.getName()))
                {
                    return FormValidation.warning("Project name case does not match. Did you mean '%s'?", p.getName());
                }
            } catch (IllegalArgumentException ex) {
                return FormValidation.error(ex.getMessage());
            } catch (IOException ex) {
                return FormValidation.error(ex.getMessage());
            }
            return FormValidation.ok();
        }
        
        /**
         * Check that the releaseVersion field is not empty.
         * @param releaseVersion The name of the project.
         * @return Ok if not empty, error otherwise.
         * @throws java.io.IOException
         * @throws javax.servlet.ServletException
         */
        public FormValidation doCheckReleaseVersion(@QueryParameter String releaseVersion) throws IOException, ServletException {
            if ("".equals(releaseVersion))
                return FormValidation.error("Please provide a release version.");
            return FormValidation.ok();
        }
        
        /**
         * Check that the releaseNotesFile field is not empty.
         * @param releaseNotesFile The name of the project.
         * @return Ok if not empty, error otherwise.
         * @throws java.io.IOException
         * @throws javax.servlet.ServletException
         */
        public FormValidation doCheckReleaseNotesFile(@QueryParameter String releaseNotesFile) throws IOException, ServletException {
            if ("".equals(releaseNotesFile))
                return FormValidation.error("Please provide a project notes file.");
            return FormValidation.ok();
        }
        
        /**
         * Check that the env field is not empty.
         * @param env The name of the project.
         * @return Ok if not empty, error otherwise.
         * @throws java.io.IOException
         * @throws javax.servlet.ServletException
         */
        public FormValidation doCheckEnv(@QueryParameter String env) throws IOException, ServletException {
            setGlobalConfiguration();
            // TODO: Extract this to be shared between plugins
            // TODO: Deduplicate this with project check
            String environment = env.trim(); 
            if (environment.isEmpty()) {
                return FormValidation.error("Please provide an environment name.");
            }
            OctopusApi api = new OctopusApi(octopusHost, apiKey);
            try {
                com.octopusdeploy.api.Environment e = api.getEnvironmentByName(environment, true);
                if (e == null)
                {
                    return FormValidation.error("Environment not found.");
                }
                if (!environment.equals(e.getName()))
                {
                    return FormValidation.warning("Environment name case does not match. Did you mean '%s'?", e.getName());
                }
            } catch (IllegalArgumentException ex) {
                return FormValidation.error(ex.getMessage());
            } catch (IOException ex) {
                return FormValidation.error(ex.getMessage());
            }
            return FormValidation.ok();
        }
    }
}

