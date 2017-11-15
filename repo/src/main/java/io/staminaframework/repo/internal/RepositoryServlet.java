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

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.log.LogService;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Servlet serving repository content.
 *
 * @author Stamina Framework developers
 */
@Component(factory = "io.staminaframework.repo.servlet",
        service = Servlet.class)
public class RepositoryServlet extends HttpServlet {
    @Reference
    private LogService logService;
    private final AtomicBoolean enabled = new AtomicBoolean();
    private Path repositoryDir;

    @Activate
    void activate(Map<String, Object> props) {
        final String repositoryPath = (String) props.get("repository.path");
        repositoryDir = FileSystems.getDefault().getPath(repositoryPath);
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        logService.log(LogService.LOG_DEBUG,
                "Access to repository is " + (enabled ? "enabled" : "disabled") + ": " + repositoryDir);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!enabled.get()) {
            logService.log(LogService.LOG_DEBUG,
                    "Prevent client from accessing disabled repository servlet: " + repositoryDir);
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        String artifactPath = req.getPathInfo();
        if (artifactPath == null || "/".equals(artifactPath)) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        if (artifactPath.startsWith("/")) {
            artifactPath = artifactPath.substring(1);
        }

        final Path artifactFile = repositoryDir.resolve(artifactPath);
        if (!Files.exists(artifactFile)
                || !Files.isRegularFile(artifactFile)
                || !artifactFile.toRealPath().startsWith(repositoryDir)) {
            logService.log(LogService.LOG_WARNING, "Artifact not found: " + artifactFile);
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        logService.log(LogService.LOG_INFO, "Access granted to artifact: " + artifactFile);
        resp.setContentLengthLong(Files.size(artifactFile));
        Files.copy(artifactFile, resp.getOutputStream());
    }
}
