package com.mathworks.ci.utilities;

/**
 * Copyright 2024, The MathWorks Inc.
 *
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.HashMap;
import org.apache.commons.lang.RandomStringUtils;

import hudson.FilePath;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.model.Computer;
import hudson.util.ArgumentListBuilder;

import com.mathworks.ci.Utilities;
import com.mathworks.ci.MatlabExecutionException;
import com.mathworks.ci.MatlabBuilderConstants;
import com.mathworks.ci.parameters.MatlabActionParameters;

public class MatlabCommandRunner {
    private MatlabActionParameters params;
    private FilePath tempFolder;
    private OutputStream stdOut;
    private Map<String,String> additionalEnvVars;

    public MatlabCommandRunner(MatlabActionParameters params) throws IOException, InterruptedException {
        this.params = params;
        this.additionalEnvVars = new HashMap<String,String>();

        // Handle case where workspace doesn't exist
        if (!params.getWorkspace().exists()) {
            params.getWorkspace().mkdirs();
        }

        // Create MATLAB folder
        FilePath matlabFolder = new FilePath(
                params.getLauncher().getChannel(), params.getWorkspace().getRemote() 
                + File.separator 
                + ".matlab");
        matlabFolder.mkdirs();

        // Create temp folder
        this.tempFolder = matlabFolder.createTempDir("tempDir", null);
        
        // If we hit an error during shutdown while cleaning up
        // there's not too much that we can do.
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    try {
                        tempFolder.deleteRecursive();
                    } catch(Exception e) {
                        System.err.println(e.toString());
                    }
                }));
    }

    /** 
     * Spawns a process to run the specified command.
     *
     * @param command The command to run
     */
    public void runMatlabCommand(String command) throws IOException, InterruptedException, MatlabExecutionException {

        this.params.getTaskListener().getLogger()
            .println("\n#################### Starting command output ####################");

        // Prepare the executable
        FilePath exePath = prepareRunnerExecutable();

        // Create the script file
        FilePath scriptFile = createFileWithContent(command);
        String cmd = "setenv('MW_ORIG_WORKING_FOLDER', cd('"
            + this.tempFolder.getRemote()
            + "'));"
            + scriptFile.getBaseName();

        // Create command
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add(exePath.getRemote());
        args.add(cmd);
        args.add(this.params.getStartupOptions().split(" "));

        // Add custom environment vars
        EnvVars env = getEnvVars();
        Utilities.addMatlabToEnvPathFromAxis(
                Computer.currentComputer(), 
                this.params.getTaskListener(), 
                env);

        ProcStarter proc = this.params.getLauncher().launch()
            .envs(env)
            .cmds(args);
        if (this.stdOut == null) {
            proc.stdout(this.params.getTaskListener());
        } else {
            proc.stdout(this.stdOut);
        }
        proc.pwd(this.params.getWorkspace());

        int code = proc.join();

        if (code != 0) {
            throw new MatlabExecutionException(code);
        }
    }

    /**
     * Redirects stdout.
     *
     * @param out the OutputStream to write to
     */
    public void redirectStdOut(OutputStream out) {
        this.stdOut = out;
    }

    /**
     * Adds an environment variable.
     *
     * @param key the environment variable name
     * @param value the environment variable value
     */
    public void addEnvironmentVariable(String key, String value) {
        additionalEnvVars.put(key, value);
    }

    public EnvVars getEnvVars() {
        EnvVars env = new EnvVars(this.params.getEnvVars());
        env.putAll(additionalEnvVars); 
        return env;
    }

    /**
     * Copies a resource into the temporary folder.
     *
     * @param sourceFile the name of a resource the class loader can find.
     * @param targetFile the name of the file to create in the temp folder.
     * @return the FilePath to the new location in the temp folder.
     */
    public FilePath copyFileToTempFolder(String sourceFile, String targetFile) throws IOException, InterruptedException {
        final ClassLoader classLoader = getClass().getClassLoader();
        FilePath targetFilePath = new FilePath(this.tempFolder, targetFile);
        InputStream in = classLoader.getResourceAsStream(sourceFile);
        targetFilePath.copyFrom(in);
        targetFilePath.chmod(0755);

        return targetFilePath;
    }

    public FilePath getTempFolder() {
        return tempFolder;
    }

    /**
     * Creates a file with the specified content in the temporary folder.
     *
     * Additionally, the file content will be prefixed with a statement returning to the MATLAB starting folder.
     *
     * @param content string that represents the content of the file.
     * @return the FilePath to the script file that is created.
     */
    protected FilePath createFileWithContent(String content) throws IOException, InterruptedException {
        String fileName = "script_" + RandomStringUtils.randomAlphanumeric(8) + ".m";
        FilePath scriptFile = new FilePath(this.tempFolder, fileName);

        String expandedContent = getEnvVars().expand(content);
        String finalContent = "cd(getenv('MW_ORIG_WORKING_FOLDER'));\n"
            + expandedContent;

        this.params.getTaskListener().getLogger()
            .println("Generating MATLAB script with content:\n" + expandedContent + "\n\n");

        scriptFile.write(finalContent, "UTF-8");

        return scriptFile;
    }

    /**
     * Copies platform specific runner file into the temporary folder.
     *
     * @return the FilePath to the runner executable
     */
    protected FilePath prepareRunnerExecutable() throws IOException, InterruptedException {
        Launcher launcher = this.params.getLauncher();
        if (launcher.isUnix()) {
            // Run uname to check if we're on Linux
            ByteArrayOutputStream kernelStream = new ByteArrayOutputStream();
            launcher.launch()
                .cmds("uname")
                .masks(true)
                .stdout(kernelStream)
                .join();

            String runnerSource;
            if (kernelStream.toString("UTF-8").contains("Linux")) {
                runnerSource = "glnxa64/run-matlab-command";
            } else {
                runnerSource = "maci64/run-matlab-command";
            }

            String dest = "run-matlab-command";
            copyFileToTempFolder(runnerSource, dest);

            return new FilePath(this.tempFolder, dest);
        } 

        // Windows
        String dest = "run-matlab-command.exe";
        copyFileToTempFolder("win64/run-matlab-command.exe", dest);
        return new FilePath(this.tempFolder, dest);
    }
}
