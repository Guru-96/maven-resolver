/*******************************************************************************
 * Copyright (c) 2010, 2013 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Sonatype, Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.aether.internal.impl;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactProperties;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.collection.DependencyManagement;
import org.eclipse.aether.collection.DependencyManager;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.impl.ArtifactDescriptorReader;
import org.eclipse.aether.internal.impl.DefaultDependencyCollector;
import org.eclipse.aether.internal.test.util.DependencyGraphParser;
import org.eclipse.aether.internal.test.util.TestLoggerFactory;
import org.eclipse.aether.internal.test.util.TestUtils;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;
import org.eclipse.aether.util.graph.manager.ClassicDependencyManager;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.junit.Before;
import org.junit.Test;

/**
 */
public class DefaultDependencyCollectorTest
{

    private DefaultDependencyCollector collector;

    private DefaultRepositorySystemSession session;

    private DependencyGraphParser parser;

    private RemoteRepository repository;

    private IniArtifactDescriptorReader newReader( String prefix )
    {
        return new IniArtifactDescriptorReader( "artifact-descriptions/" + prefix );
    }

    private Dependency newDep( String coords )
    {
        return newDep( coords, "" );
    }

    private Dependency newDep( String coords, String scope )
    {
        return new Dependency( new DefaultArtifact( coords ), scope );
    }

    @Before
    public void setup()
        throws IOException
    {
        session = TestUtils.newSession();

        collector = new DefaultDependencyCollector();
        collector.setArtifactDescriptorReader( newReader( "" ) );
        collector.setVersionRangeResolver( new StubVersionRangeResolver() );
        collector.setRemoteRepositoryManager( new StubRemoteRepositoryManager() );
        collector.setLoggerFactory( new TestLoggerFactory() );

        parser = new DependencyGraphParser( "artifact-descriptions/" );

        repository = new RemoteRepository.Builder( "id", "default", "file:///" ).build();
    }

    private static void assertEqualSubtree( DependencyNode expected, DependencyNode actual )
    {
        assertEqualSubtree( expected, actual, new LinkedList<DependencyNode>() );
    }

    private static void assertEqualSubtree( DependencyNode expected, DependencyNode actual,
                                            LinkedList<DependencyNode> parents )
    {
        assertEquals( "path: " + parents, expected.getDependency(), actual.getDependency() );

        if ( actual.getDependency() != null )
        {
            Artifact artifact = actual.getDependency().getArtifact();
            for ( DependencyNode parent : parents )
            {
                if ( parent.getDependency() != null && artifact.equals( parent.getDependency().getArtifact() ) )
                {
                    return;
                }
            }
        }

        parents.addLast( expected );

        assertEquals( "path: " + parents + ", expected: " + expected.getChildren() + ", actual: "
                          + actual.getChildren(), expected.getChildren().size(), actual.getChildren().size() );

        Iterator<DependencyNode> iterator1 = expected.getChildren().iterator();
        Iterator<DependencyNode> iterator2 = actual.getChildren().iterator();

        while ( iterator1.hasNext() )
        {
            assertEqualSubtree( iterator1.next(), iterator2.next(), parents );
        }

        parents.removeLast();
    }

    private Dependency dep( DependencyNode root, int... coords )
    {
        return path( root, coords ).getDependency();
    }

    private DependencyNode path( DependencyNode root, int... coords )
    {
        try
        {
            DependencyNode node = root;
            for ( int i = 0; i < coords.length; i++ )
            {
                node = node.getChildren().get( coords[i] );
            }

            return node;
        }
        catch ( IndexOutOfBoundsException e )
        {
            throw new IllegalArgumentException( "Illegal coordinates for child", e );
        }
        catch ( NullPointerException e )
        {
            throw new IllegalArgumentException( "Illegal coordinates for child", e );
        }
    }

    @Test
    public void testSimpleCollection()
        throws IOException, DependencyCollectionException
    {
        Dependency dependency = newDep( "gid:aid:ext:ver", "compile" );
        CollectRequest request = new CollectRequest( dependency, Arrays.asList( repository ) );
        CollectResult result = collector.collectDependencies( session, request );

        assertEquals( 0, result.getExceptions().size() );

        DependencyNode root = result.getRoot();
        Dependency newDependency = root.getDependency();

        assertEquals( dependency, newDependency );
        assertEquals( dependency.getArtifact(), newDependency.getArtifact() );

        assertEquals( 1, root.getChildren().size() );

        Dependency expect = newDep( "gid:aid2:ext:ver", "compile" );
        assertEquals( expect, root.getChildren().get( 0 ).getDependency() );
    }

    @Test
    public void testMissingDependencyDescription()
        throws IOException
    {
        CollectRequest request =
            new CollectRequest( newDep( "missing:description:ext:ver" ), Arrays.asList( repository ) );
        try
        {
            collector.collectDependencies( session, request );
            fail( "expected exception" );
        }
        catch ( DependencyCollectionException e )
        {
            CollectResult result = e.getResult();
            assertSame( request, result.getRequest() );
            assertNotNull( result.getExceptions() );
            assertEquals( 1, result.getExceptions().size() );

            assertTrue( result.getExceptions().get( 0 ) instanceof ArtifactDescriptorException );

            assertEquals( request.getRoot(), result.getRoot().getDependency() );
        }
    }

    @Test
    public void testDuplicates()
        throws IOException, DependencyCollectionException
    {
        Dependency dependency = newDep( "duplicate:transitive:ext:dependency" );
        CollectRequest request = new CollectRequest( dependency, Arrays.asList( repository ) );

        CollectResult result = collector.collectDependencies( session, request );

        assertEquals( 0, result.getExceptions().size() );

        DependencyNode root = result.getRoot();
        Dependency newDependency = root.getDependency();

        assertEquals( dependency, newDependency );
        assertEquals( dependency.getArtifact(), newDependency.getArtifact() );

        assertEquals( 2, root.getChildren().size() );

        Dependency dep = newDep( "gid:aid:ext:ver", "compile" );
        assertEquals( dep, dep( root, 0 ) );

        dep = newDep( "gid:aid2:ext:ver", "compile" );
        assertEquals( dep, dep( root, 1 ) );
        assertEquals( dep, dep( root, 0, 0 ) );
        assertEquals( dep( root, 1 ), dep( root, 0, 0 ) );
    }

    @Test
    public void testEqualSubtree()
        throws IOException, DependencyCollectionException
    {
        DependencyNode root = parser.parseResource( "expectedSubtreeComparisonResult.txt" );
        Dependency dependency = root.getDependency();
        CollectRequest request = new CollectRequest( dependency, Arrays.asList( repository ) );

        CollectResult result = collector.collectDependencies( session, request );
        assertEqualSubtree( root, result.getRoot() );
    }

    @Test
    public void testCyclicDependencies()
        throws Exception
    {
        DependencyNode root = parser.parseResource( "cycle.txt" );
        CollectRequest request = new CollectRequest( root.getDependency(), Arrays.asList( repository ) );
        CollectResult result = collector.collectDependencies( session, request );
        assertEqualSubtree( root, result.getRoot() );
    }

    @Test
    public void testCyclicDependenciesBig()
        throws Exception
    {
        CollectRequest request = new CollectRequest( newDep( "1:2:pom:5.50-SNAPSHOT" ), Arrays.asList( repository ) );
        collector.setArtifactDescriptorReader( newReader( "cycle-big/" ) );
        CollectResult result = collector.collectDependencies( session, request );
        assertNotNull( result.getRoot() );
        // we only care about the performance here, this test must not hang or run out of mem
    }

    @Test
    public void testCyclicProjects()
        throws Exception
    {
        CollectRequest request = new CollectRequest( newDep( "test:a:2" ), Arrays.asList( repository ) );
        collector.setArtifactDescriptorReader( newReader( "versionless-cycle/" ) );
        CollectResult result = collector.collectDependencies( session, request );
        DependencyNode a1 = path( result.getRoot(), 0, 0 );
        assertEquals( "a", a1.getArtifact().getArtifactId() );
        assertEquals( "1", a1.getArtifact().getVersion() );
        for ( DependencyNode child : a1.getChildren() )
        {
            assertFalse( "1".equals( child.getArtifact().getVersion() ) );
        }
    }

    @Test
    public void testPartialResultOnError()
        throws IOException
    {
        DependencyNode root = parser.parseResource( "expectedPartialSubtreeOnError.txt" );

        Dependency dependency = root.getDependency();
        CollectRequest request = new CollectRequest( dependency, Arrays.asList( repository ) );

        CollectResult result;
        try
        {
            result = collector.collectDependencies( session, request );
            fail( "expected exception " );
        }
        catch ( DependencyCollectionException e )
        {
            result = e.getResult();

            assertSame( request, result.getRequest() );
            assertNotNull( result.getExceptions() );
            assertEquals( 1, result.getExceptions().size() );

            assertTrue( result.getExceptions().get( 0 ) instanceof ArtifactDescriptorException );

            assertEqualSubtree( root, result.getRoot() );
        }
    }

    @Test
    public void testCollectMultipleDependencies()
        throws IOException, DependencyCollectionException
    {
        Dependency root1 = newDep( "gid:aid:ext:ver", "compile" );
        Dependency root2 = newDep( "gid:aid2:ext:ver", "compile" );
        List<Dependency> dependencies = Arrays.asList( root1, root2 );
        CollectRequest request = new CollectRequest( dependencies, null, Arrays.asList( repository ) );
        CollectResult result = collector.collectDependencies( session, request );

        assertEquals( 0, result.getExceptions().size() );
        assertEquals( 2, result.getRoot().getChildren().size() );
        assertEquals( root1, dep( result.getRoot(), 0 ) );

        assertEquals( 1, path( result.getRoot(), 0 ).getChildren().size() );
        assertEquals( root2, dep( result.getRoot(), 0, 0 ) );

        assertEquals( 0, path( result.getRoot(), 1 ).getChildren().size() );
        assertEquals( root2, dep( result.getRoot(), 1 ) );
    }

    @Test
    public void testArtifactDescriptorResolutionNotRestrictedToRepoHostingSelectedVersion()
        throws Exception
    {
        RemoteRepository repo2 = new RemoteRepository.Builder( "test", "default", "file:///" ).build();

        final List<RemoteRepository> repos = new ArrayList<RemoteRepository>();

        collector.setArtifactDescriptorReader( new ArtifactDescriptorReader()
        {
            public ArtifactDescriptorResult readArtifactDescriptor( RepositorySystemSession session,
                                                                    ArtifactDescriptorRequest request )
                throws ArtifactDescriptorException
            {
                repos.addAll( request.getRepositories() );
                return new ArtifactDescriptorResult( request );
            }
        } );

        List<Dependency> dependencies = Arrays.asList( newDep( "verrange:parent:jar:1[1,)", "compile" ) );
        CollectRequest request = new CollectRequest( dependencies, null, Arrays.asList( repository, repo2 ) );
        CollectResult result = collector.collectDependencies( session, request );

        assertEquals( 0, result.getExceptions().size() );
        assertEquals( 2, repos.size() );
        assertEquals( "id", repos.get( 0 ).getId() );
        assertEquals( "test", repos.get( 1 ).getId() );
    }

    @Test
    public void testManagedVersionScope()
        throws IOException, DependencyCollectionException
    {
        Dependency dependency = newDep( "managed:aid:ext:ver" );
        CollectRequest request = new CollectRequest( dependency, Arrays.asList( repository ) );

        session.setDependencyManager( new ClassicDependencyManager() );

        CollectResult result = collector.collectDependencies( session, request );

        assertEquals( 0, result.getExceptions().size() );

        DependencyNode root = result.getRoot();

        assertEquals( dependency, dep( root ) );
        assertEquals( dependency.getArtifact(), dep( root ).getArtifact() );

        assertEquals( 1, root.getChildren().size() );
        Dependency expect = newDep( "gid:aid:ext:ver", "compile" );
        assertEquals( expect, dep( root, 0 ) );

        assertEquals( 1, path( root, 0 ).getChildren().size() );
        expect = newDep( "gid:aid2:ext:managedVersion", "managedScope" );
        assertEquals( expect, dep( root, 0, 0 ) );
    }

    @Test
    public void testDependencyManagement()
        throws IOException, DependencyCollectionException
    {
        collector.setArtifactDescriptorReader( newReader( "managed/" ) );

        DependencyNode root = parser.parseResource( "expectedSubtreeComparisonResult.txt" );
        TestDependencyManager depMgmt = new TestDependencyManager();
        depMgmt.add( dep( root, 0 ), "managed", null, null );
        depMgmt.add( dep( root, 0, 1 ), "managed", "managed", null );
        depMgmt.add( dep( root, 1 ), null, null, "managed" );
        session.setDependencyManager( depMgmt );

        // collect result will differ from expectedSubtreeComparisonResult.txt
        // set localPath -> no dependency traversal
        CollectRequest request = new CollectRequest( dep( root ), Arrays.asList( repository ) );
        CollectResult result = collector.collectDependencies( session, request );

        DependencyNode node = result.getRoot();
        assertEquals( "managed", dep( node, 0, 1 ).getArtifact().getVersion() );
        assertEquals( "managed", dep( node, 0, 1 ).getScope() );

        assertEquals( "managed", dep( node, 1 ).getArtifact().getProperty( ArtifactProperties.LOCAL_PATH, null ) );
        assertEquals( "managed", dep( node, 0, 0 ).getArtifact().getProperty( ArtifactProperties.LOCAL_PATH, null ) );
    }

    @Test
    public void testDependencyManagement_VerboseMode()
        throws Exception
    {
        String depId = "gid:aid2:ext";
        TestDependencyManager depMgmt = new TestDependencyManager();
        depMgmt.version( depId, "managedVersion" );
        depMgmt.scope( depId, "managedScope" );
        depMgmt.optional( depId, Boolean.TRUE );
        depMgmt.path( depId, "managedPath" );
        depMgmt.exclusions( depId, new Exclusion( "gid", "aid", "*", "*" ) );
        session.setDependencyManager( depMgmt );
        session.setConfigProperty( DependencyManagerUtils.CONFIG_PROP_VERBOSE, Boolean.TRUE );

        CollectRequest request = new CollectRequest().setRoot( newDep( "gid:aid:ver" ) );
        CollectResult result = collector.collectDependencies( session, request );
        DependencyNode node = result.getRoot().getChildren().get( 0 );
        assertEquals( DependencyNode.MANAGED_VERSION | DependencyNode.MANAGED_SCOPE | DependencyNode.MANAGED_OPTIONAL
            | DependencyNode.MANAGED_PROPERTIES | DependencyNode.MANAGED_EXCLUSIONS, node.getManagedBits() );
        assertEquals( "ver", DependencyManagerUtils.getPremanagedVersion( node ) );
        assertEquals( "compile", DependencyManagerUtils.getPremanagedScope( node ) );
        assertEquals( Boolean.FALSE, DependencyManagerUtils.getPremanagedOptional( node ) );
    }

    static class TestDependencyManager
        implements DependencyManager
    {

        private Map<String, String> versions = new HashMap<String, String>();

        private Map<String, String> scopes = new HashMap<String, String>();

        private Map<String, Boolean> optionals = new HashMap<String, Boolean>();

        private Map<String, String> paths = new HashMap<String, String>();

        private Map<String, Collection<Exclusion>> exclusions = new HashMap<String, Collection<Exclusion>>();

        public void add( Dependency d, String version, String scope, String localPath )
        {
            String id = toKey( d );
            version( id, version );
            scope( id, scope );
            path( id, localPath );
        }

        public void version( String id, String version )
        {
            versions.put( id, version );
        }

        public void scope( String id, String scope )
        {
            scopes.put( id, scope );
        }

        public void optional( String id, Boolean optional )
        {
            optionals.put( id, optional );
        }

        public void path( String id, String path )
        {
            paths.put( id, path );
        }

        public void exclusions( String id, Exclusion... exclusions )
        {
            this.exclusions.put( id, exclusions != null ? Arrays.asList( exclusions ) : null );
        }

        public DependencyManagement manageDependency( Dependency d )
        {
            String id = toKey( d );
            DependencyManagement mgmt = new DependencyManagement();
            mgmt.setVersion( versions.get( id ) );
            mgmt.setScope( scopes.get( id ) );
            mgmt.setOptional( optionals.get( id ) );
            String path = paths.get( id );
            if ( path != null )
            {
                mgmt.setProperties( Collections.singletonMap( ArtifactProperties.LOCAL_PATH, path ) );
            }
            mgmt.setExclusions( exclusions.get( id ) );
            return mgmt;
        }

        private String toKey( Dependency dependency )
        {
            return ArtifactIdUtils.toVersionlessId( dependency.getArtifact() );
        }

        public DependencyManager deriveChildManager( DependencyCollectionContext context )
        {
            return this;
        }

    }

}
