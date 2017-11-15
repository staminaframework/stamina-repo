/*
 * Copyright (c) 2017 Stamina Framework developers.
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

package io.staminaframework.repo.internal;

import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentConstants;
import org.osgi.service.component.ComponentFactory;
import org.osgi.service.component.ComponentInstance;
import org.osgi.service.component.annotations.*;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;
import org.osgi.service.log.LogService;

import javax.servlet.Servlet;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.*;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardWatchEventKinds.*;

/**
 * This component handles a repository path.
 *
 * @author Stamina Framework developers
 */
@Component(configurationPid = "io.staminaframework.repo",
        configurationPolicy = ConfigurationPolicy.REQUIRE)
public class RepositoryManager {
    /**
     * Component configuration.
     *
     * @author Stamina Framework developers
     */
    public @interface Config {
        /**
         * Repository path.
         */
        String path();

        /**
         * Repository identifier (must be unique).
         */
        String id();

        /**
         * Repository name (may be <code>null</code> or empty).
         */
        String name() default "";
    }

    @Reference
    private LogService logService;
    @Reference(target = "(" + ComponentConstants.COMPONENT_FACTORY + "=io.staminaframework.repo.servlet)")
    private ComponentFactory componentFactory;
    private ComponentInstance servletInstance;
    private ScheduledExecutorService scheduler;
    private volatile Future<?> indexerTask;
    private Path repositoryDir;
    private String repositoryName;
    private String repositoryId;
    private Thread repositoryWatcher;

    @Activate
    void activate(BundleContext bundleContext, Config config) throws IOException, InvalidSyntaxException {
        if (config.id() == null || config.id().length() == 0) {
            throw new IllegalArgumentException("Missing repository id");
        }
        if (config.path() == null) {
            throw new IllegalArgumentException("Missing repository path");
        }

        repositoryId = config.id();
        repositoryDir = FileSystems.getDefault().getPath(config.path());
        repositoryName = config.name();
        if (!Files.exists(repositoryDir)) {
            logService.log(LogService.LOG_INFO, "Creating repository directory: " + repositoryDir);
            Files.createDirectories(repositoryDir);
        }
        repositoryDir = repositoryDir.toRealPath();

        // Check if a servlet exists with this name.
        final ServiceReference<?>[] servletRefs =
                bundleContext.getAllServiceReferences(Servlet.class.getName(), "(repository.id=" + repositoryId + ")");
        if (servletRefs != null && servletRefs.length != 0) {
            throw new IllegalArgumentException("Already existing repository id: " + repositoryId);
        }

        final Dictionary<String, Object> servletProps = new Hashtable<>(4);
        servletProps.put("repository.path", repositoryDir.toString());
        servletProps.put("repository.id", repositoryId);
        servletProps.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN, "/" + repositoryId + "/*");
        servletProps.put(HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME, "io.staminaframework.repo");
        logService.log(LogService.LOG_INFO, "Creating repository servlet: " + repositoryId);
        servletInstance = componentFactory.newInstance(servletProps);

        scheduler = Executors.newScheduledThreadPool(1, r -> {
            final Thread t = new Thread(r, "Stamina Repository Indexer: " + repositoryId);
            t.setDaemon(false);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });

        repositoryWatcher = new RepositoryWatcher();
        repositoryWatcher.start();
        scheduleIndexRepository();
    }

    @Deactivate
    void deactivate() {
        if (repositoryWatcher != null) {
            repositoryWatcher.interrupt();
            try {
                repositoryWatcher.join(1000 * 4);
            } catch (InterruptedException ignore) {
            }
            repositoryWatcher = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(1000, TimeUnit.SECONDS);
            } catch (InterruptedException ignore) {
            }
            scheduler = null;
        }
        if (servletInstance != null) {
            servletInstance.dispose();
            servletInstance = null;
        }
    }

    private void scheduleIndexRepository() {
        if (indexerTask != null) {
            indexerTask.cancel(true);
        }
        logService.log(LogService.LOG_DEBUG, "Scheduling repository indexing: " + repositoryDir);
        indexerTask = scheduler.schedule(this::indexRepository, 1, TimeUnit.SECONDS);
    }

    private void indexRepository() {
        final RepositoryServlet repoServlet = (RepositoryServlet) servletInstance.getInstance();
        if (repoServlet == null) {
            return;
        }
        repoServlet.setEnabled(false);
        logService.log(LogService.LOG_INFO, "Indexing repository: " + repositoryDir);

        final RepositoryIndexer indexer = new RepositoryIndexer();
        Path repositoryFile = null;
        boolean keepIndexFile = false;
        try {
            repositoryFile = Files.createTempFile("obr-", ".xml");
            indexer.indexRepository(repositoryDir, repositoryFile, repositoryName);
            repositoryFile = Files.move(repositoryFile, repositoryDir.resolve("obr.xml"), REPLACE_EXISTING, ATOMIC_MOVE);
            keepIndexFile = true;
        } catch (ClosedByInterruptException ignore) {
        } catch (IOException e) {
            logService.log(LogService.LOG_WARNING,
                    "Error while indexing repository: " + repositoryDir, e);
        }
        if (!keepIndexFile) {
            try {
                Files.deleteIfExists(repositoryFile);
            } catch (IOException ignore) {
            }
        }
        repoServlet.setEnabled(true);
    }

    private class RepositoryWatcher extends Thread {
        public RepositoryWatcher() {
            super();
            setName("Stamina Repository Watcher: " + repositoryId);
            setPriority(Thread.MIN_PRIORITY);
            setDaemon(true);
        }

        @Override
        public void run() {
            try (final WatchService watchService = FileSystems.getDefault().newWatchService()) {
                logService.log(LogService.LOG_INFO, "Monitoring repository: " + repositoryDir);
                repositoryDir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);

                boolean running = true;
                while (running) {
                    try {
                        final WatchKey key = watchService.take();
                        boolean readyToIndex = true;
                        for (final WatchEvent<?> e : key.pollEvents()) {
                            if (e.kind() == OVERFLOW) {
                                readyToIndex = false;
                                break;
                            }
                            final WatchEvent<Path> ev = (WatchEvent<Path>) e;
                            if ("obr.xml".equals(ev.context().toString())) {
                                readyToIndex = false;
                                break;
                            }
                        }
                        if (readyToIndex) {
                            scheduleIndexRepository();
                        }
                        key.reset();
                    } catch (ClosedWatchServiceException | InterruptedException e) {
                        running = false;
                    }
                }
            } catch (IOException e) {
                logService.log(LogService.LOG_ERROR,
                        "Failed to initialize repository monitoring: " + repositoryDir, e);
            }
        }
    }
}
