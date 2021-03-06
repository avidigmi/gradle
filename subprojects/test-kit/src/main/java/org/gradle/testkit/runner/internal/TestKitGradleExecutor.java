/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.testkit.runner.internal;

import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleDistribution;
import org.gradle.testkit.runner.TaskOutcome;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.events.ProgressEvent;
import org.gradle.tooling.events.ProgressListener;
import org.gradle.tooling.events.task.*;
import org.gradle.tooling.internal.consumer.DefaultBuildLauncher;
import org.gradle.tooling.internal.consumer.DefaultGradleConnector;
import org.gradle.wrapper.GradleUserHomeLookup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.gradle.testkit.runner.TaskOutcome.*;

public class TestKitGradleExecutor implements GradleExecutor {

    public static final String TEST_KIT_DAEMON_DIR_NAME = "test-kit-daemon";
    private final GradleDistribution gradleDistribution;

    public TestKitGradleExecutor(GradleDistribution gradleDistribution) {
        this.gradleDistribution = gradleDistribution;
        registerShutdownHook();
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                DefaultGradleConnector.close();
            }
        }));
    }

    public GradleExecutionResult run(GradleExecutionParameters parameters) {
        final ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
        final ByteArrayOutputStream standardError = new ByteArrayOutputStream();
        final List<BuildTask> tasks = new ArrayList<BuildTask>();

        GradleConnector gradleConnector = buildConnector(parameters.getGradleUserHome(), parameters.getProjectDir(), parameters.isDaemon());
        ProjectConnection connection = null;

        try {
            connection = gradleConnector.connect();
            DefaultBuildLauncher launcher = (DefaultBuildLauncher) connection.newBuild();
            launcher.setStandardOutput(determineLauncherOutputStream(standardOutput, parameters.getStandardOutput()));
            launcher.setStandardError(determineLauncherOutputStream(standardError, parameters.getStandardError()));
            launcher.addProgressListener(new TaskExecutionProgressListener(tasks));

            launcher.withArguments(parameters.getBuildArgs().toArray(new String[parameters.getBuildArgs().size()]));
            launcher.setJvmArguments(parameters.getJvmArgs().toArray(new String[parameters.getJvmArgs().size()]));

            launcher.withInjectedClassPath(parameters.getInjectedClassPath());

            launcher.run();
        } catch (Throwable t) {
            return new GradleExecutionResult(standardOutput, standardError, tasks, t);
        } finally {
            if (connection != null) {
                connection.close();
            }
        }

        return new GradleExecutionResult(standardOutput, standardError, tasks);
    }

    private OutputStream determineLauncherOutputStream(OutputStream outputStream, Writer writer) {
        if (writer != null) {
            return new TeeOutputStreamWriter(outputStream, writer);
        }

        return outputStream;
    }

    private GradleConnector buildConnector(File gradleUserHome, File projectDir, boolean daemon) {
        DefaultGradleConnector gradleConnector = (DefaultGradleConnector) GradleConnector.newConnector();
        useGradleDistribution(gradleConnector);
        gradleConnector.useGradleUserHomeDir(gradleUserHome);
        gradleConnector.useDistributionBaseDir(GradleUserHomeLookup.gradleUserHome());
        gradleConnector.daemonBaseDir(new File(gradleUserHome, TEST_KIT_DAEMON_DIR_NAME));
        gradleConnector.forProjectDirectory(projectDir);
        gradleConnector.searchUpwards(false);
        gradleConnector.daemonMaxIdleTime(120, TimeUnit.SECONDS);
        gradleConnector.embedded(!daemon);
        return gradleConnector;
    }

    private void useGradleDistribution(GradleConnector gradleConnector) {
        if(gradleDistribution instanceof InstalledGradleDistribution) {
            gradleConnector.useInstallation(((InstalledGradleDistribution) gradleDistribution).getGradleHome());
        } else if(gradleDistribution instanceof URILocatedGradleDistribution) {
            gradleConnector.useDistribution(((URILocatedGradleDistribution) gradleDistribution).getLocation());
        } else if(gradleDistribution instanceof VersionBasedGradleDistribution) {
            gradleConnector.useGradleVersion(((VersionBasedGradleDistribution) gradleDistribution).getGradleVersion());
        }
    }

    private class TaskExecutionProgressListener implements ProgressListener {
        private final List<BuildTask> tasks;
        private final Map<String, Integer> order = new HashMap<String, Integer>();

        public TaskExecutionProgressListener(List<BuildTask> tasks) {
            this.tasks = tasks;
        }

        public void statusChanged(ProgressEvent event) {
            if (event instanceof TaskStartEvent) {
                TaskStartEvent taskStartEvent = (TaskStartEvent) event;
                order.put(taskStartEvent.getDescriptor().getTaskPath(), tasks.size());
                tasks.add(null);
            }
            if (event instanceof TaskFinishEvent) {
                TaskFinishEvent taskFinishEvent = (TaskFinishEvent) event;
                String taskPath = taskFinishEvent.getDescriptor().getTaskPath();
                TaskOperationResult result = taskFinishEvent.getResult();
                final Integer index = order.get(taskPath);
                if (index == null) {
                    throw new IllegalStateException("Received task finish event for task " + taskPath + " without first receiving task start event");
                }
                tasks.set(index, determineBuildTask(result, taskPath));
            }
        }

        private BuildTask determineBuildTask(TaskOperationResult result, String taskPath) {
            if (isFailed(result)) {
                return createBuildTask(taskPath, FAILED);
            } else if (isSkipped(result)) {
                return createBuildTask(taskPath, SKIPPED);
            } else if (isUpToDate(result)) {
                return createBuildTask(taskPath, UP_TO_DATE);
            }

            return createBuildTask(taskPath, SUCCESS);
        }

        private BuildTask createBuildTask(String taskPath, TaskOutcome outcome) {
            return new DefaultBuildTask(taskPath, outcome);
        }

        private boolean isFailed(TaskOperationResult result) {
            return result instanceof TaskFailureResult;
        }

        private boolean isSkipped(TaskOperationResult result) {
            return result instanceof TaskSkippedResult;
        }

        private boolean isUpToDate(TaskOperationResult result) {
            return result instanceof TaskSuccessResult && ((TaskSuccessResult) result).isUpToDate();
        }
    }
}
