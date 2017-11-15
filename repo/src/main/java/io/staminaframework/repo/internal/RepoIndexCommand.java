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

import io.staminaframework.runtime.command.Command;
import io.staminaframework.runtime.command.CommandConstants;
import org.osgi.service.component.annotations.Component;

import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;

/**
 * Index a repository.
 *
 * @author Stamina Framework developers
 */
@Component(service = Command.class, property = CommandConstants.COMMAND + "=repo:index")
public class RepoIndexCommand implements Command {
    @Override
    public void help(PrintStream out) {
        out.println("Index a repository.");
        out.println("Usage: repo:index [--name <repository name>] <directory>");
    }

    @Override
    public boolean execute(Context context) throws Exception {
        Path repoDir = null;
        String repoName = null;

        if (context.arguments().length == 3 && "--name".equals(context.arguments()[0])) {
            repoName = context.arguments()[1];
            repoDir = FileSystems.getDefault().getPath(context.arguments()[2]);
        } else if (context.arguments().length == 1) {
            repoDir = FileSystems.getDefault().getPath(context.arguments()[0]);
        } else {
            help(context.out());
            return false;
        }

        context.out().println("Indexing repository: " + repoDir);
        final Path indexFile = repoDir.resolve("obr.xml");
        new RepositoryIndexer().indexRepository(repoDir, indexFile, repoName);

        return false;
    }
}
