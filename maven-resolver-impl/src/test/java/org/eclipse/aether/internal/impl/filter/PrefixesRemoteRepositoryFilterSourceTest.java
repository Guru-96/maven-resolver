/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.eclipse.aether.internal.impl.filter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.internal.impl.DefaultRepositoryLayoutProvider;
import org.eclipse.aether.internal.impl.Maven2RepositoryLayoutFactory;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.filter.RemoteRepositoryFilterSource;

import static org.eclipse.aether.internal.impl.checksum.Checksums.checksumsSelector;

/**
 * UT for {@link PrefixesRemoteRepositoryFilterSource}.
 */
public class PrefixesRemoteRepositoryFilterSourceTest extends RemoteRepositoryFilterSourceTestSupport {
    @Override
    protected RemoteRepositoryFilterSource getRemoteRepositoryFilterSource(
            DefaultRepositorySystemSession session, RemoteRepository remoteRepository) {
        DefaultRepositoryLayoutProvider layoutProvider = new DefaultRepositoryLayoutProvider(Collections.singletonMap(
                Maven2RepositoryLayoutFactory.NAME, new Maven2RepositoryLayoutFactory(checksumsSelector())));
        return new PrefixesRemoteRepositoryFilterSource(layoutProvider);
    }

    @Override
    protected void enableSource(DefaultRepositorySystemSession session) {
        session.setConfigProperty(
                "aether.remoteRepositoryFilter." + PrefixesRemoteRepositoryFilterSource.NAME, Boolean.TRUE.toString());
    }

    @Override
    protected void allowArtifact(
            DefaultRepositorySystemSession session, RemoteRepository remoteRepository, Artifact artifact) {
        try {
            Path baseDir = session.getLocalRepository()
                    .getBasedir()
                    .toPath()
                    .resolve(PrefixesRemoteRepositoryFilterSource.LOCAL_REPO_PREFIX_DIR);
            Path groupId = baseDir.resolve(PrefixesRemoteRepositoryFilterSource.PREFIXES_FILE_PREFIX
                    + remoteRepository.getId()
                    + PrefixesRemoteRepositoryFilterSource.PREFIXES_FILE_SUFFIX);
            Files.createDirectories(groupId.getParent());
            Files.write(groupId, artifact.getGroupId().replaceAll("\\.", "/").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
