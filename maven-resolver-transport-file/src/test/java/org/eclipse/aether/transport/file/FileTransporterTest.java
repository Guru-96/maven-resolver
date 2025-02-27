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
package org.eclipse.aether.transport.file;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.internal.test.util.TestFileUtils;
import org.eclipse.aether.internal.test.util.TestUtils;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.PutTask;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.NoTransporterException;
import org.eclipse.aether.transfer.TransferCancelledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 */
public class FileTransporterTest {

    private DefaultRepositorySystemSession session;

    private TransporterFactory factory;

    private Transporter transporter;

    private File repoDir;

    private RemoteRepository newRepo(String url) {
        return new RemoteRepository.Builder("test", "default", url).build();
    }

    private void newTransporter(String url) throws Exception {
        if (transporter != null) {
            transporter.close();
            transporter = null;
        }
        transporter = factory.newInstance(session, newRepo(url));
    }

    @BeforeEach
    void setUp() throws Exception {
        session = TestUtils.newSession();
        factory = new FileTransporterFactory();
        repoDir = TestFileUtils.createTempDir();
        TestFileUtils.writeString(new File(repoDir, "file.txt"), "test");
        TestFileUtils.writeString(new File(repoDir, "empty.txt"), "");
        TestFileUtils.writeString(new File(repoDir, "some space.txt"), "space");
        newTransporter(repoDir.toURI().toString());
    }

    @AfterEach
    void tearDown() {
        if (transporter != null) {
            transporter.close();
            transporter = null;
        }
        factory = null;
        session = null;
    }

    @Test
    void testClassify() {
        assertEquals(Transporter.ERROR_OTHER, transporter.classify(new FileNotFoundException()));
        assertEquals(Transporter.ERROR_NOT_FOUND, transporter.classify(new ResourceNotFoundException("test")));
    }

    @Test
    void testPeek() throws Exception {
        transporter.peek(new PeekTask(URI.create("file.txt")));
    }

    @Test
    void testPeek_NotFound() throws Exception {
        try {
            transporter.peek(new PeekTask(URI.create("missing.txt")));
            fail("Expected error");
        } catch (ResourceNotFoundException e) {
            assertEquals(Transporter.ERROR_NOT_FOUND, transporter.classify(e));
        }
    }

    @Test
    void testPeek_Closed() throws Exception {
        transporter.close();
        try {
            transporter.peek(new PeekTask(URI.create("missing.txt")));
            fail("Expected error");
        } catch (IllegalStateException e) {
            assertEquals(Transporter.ERROR_OTHER, transporter.classify(e));
        }
    }

    @Test
    void testGet_ToMemory() throws Exception {
        RecordingTransportListener listener = new RecordingTransportListener();
        GetTask task = new GetTask(URI.create("file.txt")).setListener(listener);
        transporter.get(task);
        assertEquals("test", task.getDataString());
        assertEquals(0L, listener.dataOffset);
        assertEquals(4L, listener.dataLength);
        assertEquals(1, listener.startedCount);
        assertTrue(listener.progressedCount > 0, "Count: " + listener.progressedCount);
        assertEquals(task.getDataString(), new String(listener.baos.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void testGet_ToFile() throws Exception {
        File file = TestFileUtils.createTempFile("failure");
        RecordingTransportListener listener = new RecordingTransportListener();
        GetTask task = new GetTask(URI.create("file.txt")).setDataFile(file).setListener(listener);
        transporter.get(task);
        assertEquals("test", TestFileUtils.readString(file));
        assertEquals(0L, listener.dataOffset);
        assertEquals(4L, listener.dataLength);
        assertEquals(1, listener.startedCount);
        assertTrue(listener.progressedCount > 0, "Count: " + listener.progressedCount);
        assertEquals("test", new String(listener.baos.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void testGet_EmptyResource() throws Exception {
        File file = TestFileUtils.createTempFile("failure");
        RecordingTransportListener listener = new RecordingTransportListener();
        GetTask task = new GetTask(URI.create("empty.txt")).setDataFile(file).setListener(listener);
        transporter.get(task);
        assertEquals("", TestFileUtils.readString(file));
        assertEquals(0L, listener.dataOffset);
        assertEquals(0L, listener.dataLength);
        assertEquals(1, listener.startedCount);
        assertEquals(0, listener.progressedCount);
        assertEquals("", new String(listener.baos.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void testGet_EncodedResourcePath() throws Exception {
        GetTask task = new GetTask(URI.create("some%20space.txt"));
        transporter.get(task);
        assertEquals("space", task.getDataString());
    }

    @Test
    void testGet_Fragment() throws Exception {
        GetTask task = new GetTask(URI.create("file.txt#ignored"));
        transporter.get(task);
        assertEquals("test", task.getDataString());
    }

    @Test
    void testGet_Query() throws Exception {
        GetTask task = new GetTask(URI.create("file.txt?ignored"));
        transporter.get(task);
        assertEquals("test", task.getDataString());
    }

    @Test
    void testGet_FileHandleLeak() throws Exception {
        for (int i = 0; i < 100; i++) {
            File file = TestFileUtils.createTempFile("failure");
            transporter.get(new GetTask(URI.create("file.txt")).setDataFile(file));
            assertTrue(file.delete(), i + ", " + file.getAbsolutePath());
        }
    }

    @Test
    void testGet_NotFound() throws Exception {
        try {
            transporter.get(new GetTask(URI.create("missing.txt")));
            fail("Expected error");
        } catch (ResourceNotFoundException e) {
            assertEquals(Transporter.ERROR_NOT_FOUND, transporter.classify(e));
        }
    }

    @Test
    void testGet_Closed() throws Exception {
        transporter.close();
        try {
            transporter.get(new GetTask(URI.create("file.txt")));
            fail("Expected error");
        } catch (IllegalStateException e) {
            assertEquals(Transporter.ERROR_OTHER, transporter.classify(e));
        }
    }

    @Test
    void testGet_StartCancelled() throws Exception {
        RecordingTransportListener listener = new RecordingTransportListener();
        listener.cancelStart = true;
        GetTask task = new GetTask(URI.create("file.txt")).setListener(listener);
        try {
            transporter.get(task);
            fail("Expected error");
        } catch (TransferCancelledException e) {
            assertEquals(Transporter.ERROR_OTHER, transporter.classify(e));
        }
        assertEquals(0L, listener.dataOffset);
        assertEquals(4L, listener.dataLength);
        assertEquals(1, listener.startedCount);
        assertEquals(0, listener.progressedCount);
    }

    @Test
    void testGet_ProgressCancelled() throws Exception {
        RecordingTransportListener listener = new RecordingTransportListener();
        listener.cancelProgress = true;
        GetTask task = new GetTask(URI.create("file.txt")).setListener(listener);
        try {
            transporter.get(task);
            fail("Expected error");
        } catch (TransferCancelledException e) {
            assertEquals(Transporter.ERROR_OTHER, transporter.classify(e));
        }
        assertEquals(0L, listener.dataOffset);
        assertEquals(4L, listener.dataLength);
        assertEquals(1, listener.startedCount);
        assertEquals(1, listener.progressedCount);
    }

    @Test
    void testPut_FromMemory() throws Exception {
        RecordingTransportListener listener = new RecordingTransportListener();
        PutTask task = new PutTask(URI.create("file.txt")).setListener(listener).setDataString("upload");
        transporter.put(task);
        assertEquals(0L, listener.dataOffset);
        assertEquals(6L, listener.dataLength);
        assertEquals(1, listener.startedCount);
        assertTrue(listener.progressedCount > 0, "Count: " + listener.progressedCount);
        assertEquals("upload", TestFileUtils.readString(new File(repoDir, "file.txt")));
    }

    @Test
    void testPut_FromFile() throws Exception {
        File file = TestFileUtils.createTempFile("upload");
        RecordingTransportListener listener = new RecordingTransportListener();
        PutTask task = new PutTask(URI.create("file.txt")).setListener(listener).setDataFile(file);
        transporter.put(task);
        assertEquals(0L, listener.dataOffset);
        assertEquals(6L, listener.dataLength);
        assertEquals(1, listener.startedCount);
        assertTrue(listener.progressedCount > 0, "Count: " + listener.progressedCount);
        assertEquals("upload", TestFileUtils.readString(new File(repoDir, "file.txt")));
    }

    @Test
    void testPut_EmptyResource() throws Exception {
        RecordingTransportListener listener = new RecordingTransportListener();
        PutTask task = new PutTask(URI.create("file.txt")).setListener(listener);
        transporter.put(task);
        assertEquals(0L, listener.dataOffset);
        assertEquals(0L, listener.dataLength);
        assertEquals(1, listener.startedCount);
        assertEquals(0, listener.progressedCount);
        assertEquals("", TestFileUtils.readString(new File(repoDir, "file.txt")));
    }

    @Test
    void testPut_NonExistentParentDir() throws Exception {
        RecordingTransportListener listener = new RecordingTransportListener();
        PutTask task = new PutTask(URI.create("dir/sub/dir/file.txt"))
                .setListener(listener)
                .setDataString("upload");
        transporter.put(task);
        assertEquals(0L, listener.dataOffset);
        assertEquals(6L, listener.dataLength);
        assertEquals(1, listener.startedCount);
        assertTrue(listener.progressedCount > 0, "Count: " + listener.progressedCount);
        assertEquals("upload", TestFileUtils.readString(new File(repoDir, "dir/sub/dir/file.txt")));
    }

    @Test
    void testPut_EncodedResourcePath() throws Exception {
        RecordingTransportListener listener = new RecordingTransportListener();
        PutTask task = new PutTask(URI.create("some%20space.txt"))
                .setListener(listener)
                .setDataString("OK");
        transporter.put(task);
        assertEquals(0L, listener.dataOffset);
        assertEquals(2L, listener.dataLength);
        assertEquals(1, listener.startedCount);
        assertTrue(listener.progressedCount > 0, "Count: " + listener.progressedCount);
        assertEquals("OK", TestFileUtils.readString(new File(repoDir, "some space.txt")));
    }

    @Test
    void testPut_FileHandleLeak() throws Exception {
        for (int i = 0; i < 100; i++) {
            File src = TestFileUtils.createTempFile("upload");
            File dst = new File(repoDir, "file.txt");
            transporter.put(new PutTask(URI.create("file.txt")).setDataFile(src));
            assertTrue(src.delete(), i + ", " + src.getAbsolutePath());
            assertTrue(dst.delete(), i + ", " + dst.getAbsolutePath());
        }
    }

    @Test
    void testPut_Closed() throws Exception {
        transporter.close();
        try {
            transporter.put(new PutTask(URI.create("missing.txt")));
            fail("Expected error");
        } catch (IllegalStateException e) {
            assertEquals(Transporter.ERROR_OTHER, transporter.classify(e));
        }
    }

    @Test
    void testPut_StartCancelled() throws Exception {
        RecordingTransportListener listener = new RecordingTransportListener();
        listener.cancelStart = true;
        PutTask task = new PutTask(URI.create("file.txt")).setListener(listener).setDataString("upload");
        try {
            transporter.put(task);
            fail("Expected error");
        } catch (TransferCancelledException e) {
            assertEquals(Transporter.ERROR_OTHER, transporter.classify(e));
        }
        assertEquals(0L, listener.dataOffset);
        assertEquals(6L, listener.dataLength);
        assertEquals(1, listener.startedCount);
        assertEquals(0, listener.progressedCount);
        assertFalse(new File(repoDir, "file.txt").exists());
    }

    @Test
    void testPut_ProgressCancelled() throws Exception {
        RecordingTransportListener listener = new RecordingTransportListener();
        listener.cancelProgress = true;
        PutTask task = new PutTask(URI.create("file.txt")).setListener(listener).setDataString("upload");
        try {
            transporter.put(task);
            fail("Expected error");
        } catch (TransferCancelledException e) {
            assertEquals(Transporter.ERROR_OTHER, transporter.classify(e));
        }
        assertEquals(0L, listener.dataOffset);
        assertEquals(6L, listener.dataLength);
        assertEquals(1, listener.startedCount);
        assertEquals(1, listener.progressedCount);
        assertFalse(new File(repoDir, "file.txt").exists());
    }

    @Test
    void testInit_BadProtocol() {
        assertThrows(NoTransporterException.class, () -> newTransporter("bad:/void"));
    }

    @Test
    void testInit_CaseInsensitiveProtocol() throws Exception {
        newTransporter("file:/void");
        newTransporter("FILE:/void");
        newTransporter("File:/void");
    }

    @Test
    void testInit_OpaqueUrl() throws Exception {
        testInit("file:repository", "repository");
    }

    @Test
    void testInit_OpaqueUrlTrailingSlash() throws Exception {
        testInit("file:repository/", "repository");
    }

    @Test
    void testInit_OpaqueUrlSpaces() throws Exception {
        testInit("file:repo%20space", "repo space");
    }

    @Test
    void testInit_OpaqueUrlSpacesDecoded() throws Exception {
        testInit("file:repo space", "repo space");
    }

    @Test
    void testInit_HierarchicalUrl() throws Exception {
        testInit("file:/repository", "/repository");
    }

    @Test
    void testInit_HierarchicalUrlTrailingSlash() throws Exception {
        testInit("file:/repository/", "/repository");
    }

    @Test
    void testInit_HierarchicalUrlSpaces() throws Exception {
        testInit("file:/repo%20space", "/repo space");
    }

    @Test
    void testInit_HierarchicalUrlSpacesDecoded() throws Exception {
        testInit("file:/repo space", "/repo space");
    }

    @Test
    void testInit_HierarchicalUrlRoot() throws Exception {
        testInit("file:/", "/");
    }

    @Test
    void testInit_HierarchicalUrlHostNoPath() throws Exception {
        testInit("file://host/", "/");
    }

    @Test
    void testInit_HierarchicalUrlHostPath() throws Exception {
        testInit("file://host/dir", "/dir");
    }

    private void testInit(String base, String expected) throws Exception {
        newTransporter(base);
        File exp = new File(expected).getAbsoluteFile();
        assertEquals(exp, ((FileTransporter) transporter).getBasedir());
    }
}
