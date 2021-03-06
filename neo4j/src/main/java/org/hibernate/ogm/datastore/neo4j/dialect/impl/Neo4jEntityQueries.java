/*
 * Hibernate OGM, Domain model persistence for NoSQL datastores
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.ogm.datastore.neo4j.dialect.impl;

import static org.hibernate.ogm.datastore.neo4j.dialect.impl.NodeLabel.EMBEDDED;
import static org.hibernate.ogm.datastore.neo4j.dialect.impl.NodeLabel.ENTITY;
import static org.hibernate.ogm.datastore.neo4j.query.parsing.cypherdsl.impl.CypherDSL.escapeIdentifier;
import static org.hibernate.ogm.util.impl.EmbeddedHelper.isPartOfEmbedded;
import static org.hibernate.ogm.util.impl.EmbeddedHelper.split;

import java.util.Map;

import org.hibernate.internal.util.collections.BoundedConcurrentHashMap;
import org.hibernate.ogm.model.key.spi.EntityKeyMetadata;
import org.hibernate.ogm.util.impl.ArrayHelper;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Result;

/**
 * Container for the queries related to one entity type in Neo4j. Unfortunately, we cannot use the same queries for all
 * entities, as Neo4j does not allow to parameterize on node labels which would be required, as the entity name is
 * stored as a label.
 *
 * @author Davide D'Alto
 */
public class Neo4jEntityQueries extends QueriesBase {

	private static final int CACHE_CAPACITY = 1000;
	private static final int CACHE_CONCURRENCY_LEVEL = 20;

	private final BoundedConcurrentHashMap<String, String> updateEmbeddedPropertyQueryCache;
	private final BoundedConcurrentHashMap<String, String> findAssociationQueryCache;

	private final String createEmbeddedNodeQuery;
	private final String findEntityQuery;
	private final String findEntitiesQuery;
	private final String findAssociationPartialQuery;
	private final String createEntityQuery;
	private final String removeEntityQuery;
	private final String updateEmbeddedNodeQuery;
	private final String removeEmbdeddedElementQuery;

	public Neo4jEntityQueries(EntityKeyMetadata entityKeyMetadata) {
		this.updateEmbeddedPropertyQueryCache = new BoundedConcurrentHashMap<String, String>( CACHE_CAPACITY, CACHE_CONCURRENCY_LEVEL, BoundedConcurrentHashMap.Eviction.LIRS );
		this.findAssociationQueryCache = new BoundedConcurrentHashMap<String, String>( CACHE_CAPACITY, CACHE_CONCURRENCY_LEVEL, BoundedConcurrentHashMap.Eviction.LIRS );
		this.findAssociationPartialQuery = initMatchOwnerEntityNode( entityKeyMetadata );
		this.createEmbeddedNodeQuery = initCreateEmbeddedNodeQuery( entityKeyMetadata );
		this.findEntityQuery = initFindEntityQuery( entityKeyMetadata );
		this.findEntitiesQuery = initFindEntitiesQuery( entityKeyMetadata );
		this.createEntityQuery = initCreateEntityQuery( entityKeyMetadata );
		this.removeEntityQuery = initRemoveEntityQuery( entityKeyMetadata );
		this.updateEmbeddedNodeQuery = initUpdateEmbeddedNodeQuery( entityKeyMetadata );
		this.removeEmbdeddedElementQuery = initRemoveEmbdeddedElementQuery( entityKeyMetadata );
	}

	/*
	 * Example:
	 *
	 * MATCH (owner:ENTITY:table {id: {0}})
	 */
	private static String initMatchOwnerEntityNode(EntityKeyMetadata ownerEntityKeyMetadata) {
		StringBuilder queryBuilder = new StringBuilder();
		appendMatchOwnerEntityNode( queryBuilder, ownerEntityKeyMetadata );
		return queryBuilder.toString();
	}

	/*
	 * Example:
	 *
	 * MATCH (owner:ENTITY:Car {`carId.maker`: {0}, `carId.model`: {1}}) -[r:tires]- ()
	 * RETURN r
	 *
	 * or for embedded associations:
	 *
	 * MATCH (owner:ENTITY:StoryGame {id: {0}}) -[:evilBranch]-> (:EMBEDDED) -[r:additionalEndings]-> (:EMBEDDED)
	 * RETURN r
	 */
	private String completeFindAssociationQuery(String relationshipType) {
		StringBuilder queryBuilder = new StringBuilder( findAssociationPartialQuery );
		if ( isPartOfEmbedded( relationshipType ) ) {
			String[] path = split( relationshipType );
			int index = 0;
			for ( String embeddedRelationshipType : path ) {
				queryBuilder.append( " -[" );
				if ( index == path.length - 1 ) {
					queryBuilder.append( "r" );
				}
				queryBuilder.append( ":" );
				appendRelationshipType( queryBuilder, embeddedRelationshipType );
				queryBuilder.append( "]-> (:");
				queryBuilder.append( EMBEDDED );
				queryBuilder.append( ")" );
				index++;
			}
		}
		else {
			queryBuilder.append( " -[r" );
			queryBuilder.append( ":" );
			appendRelationshipType( queryBuilder, relationshipType );
			queryBuilder.append( "]- ()" );
		}
		queryBuilder.append( " RETURN r" );
		return queryBuilder.toString();
	}

	/*
	 * Example:
	 *
	 * CREATE (n:EMBEDDED:table {id: {0}})
	 * RETURN n
	 */
	private static String initCreateEmbeddedNodeQuery(EntityKeyMetadata entityKeyMetadata) {
		StringBuilder queryBuilder = new StringBuilder( "CREATE " );
		queryBuilder.append( "(n:" );
		queryBuilder.append( EMBEDDED );
		queryBuilder.append( ":" );
		appendLabel( entityKeyMetadata, queryBuilder );
		appendProperties( entityKeyMetadata, queryBuilder );
		queryBuilder.append( ") RETURN n" );
		return queryBuilder.toString();
	}

	/* This is only the first part of the query, the one related to the owner of the embedded.
	 * We need to know the embedded columns to create the whole query.
	 *
	 * Example:
	 * MERGE (owner:ENTITY:Example {id: {0}}) MERGE (owner)
	 */
	private static String initUpdateEmbeddedNodeQuery(EntityKeyMetadata entityKeyMetadata) {
		StringBuilder queryBuilder = new StringBuilder( "MERGE " );
		appendEntityNode( "owner", entityKeyMetadata, queryBuilder );
		queryBuilder.append( " MERGE (owner)" );
		return queryBuilder.toString();
	}

	/*
	 * Example:
	 *
	 * MATCH (owner:ENTITY:table {id: {0}})
	 * RETURN owner;
	 */
	private static String initFindEntityQuery(EntityKeyMetadata entityKeyMetadata) {
		StringBuilder queryBuilder = new StringBuilder();
		appendMatchOwnerEntityNode( queryBuilder, entityKeyMetadata );
		queryBuilder.append( " RETURN owner" );
		return queryBuilder.toString();
	}

	/*
	 * Example:
	 *
	 * MATCH (n:ENTITY:table )
	 * RETURN n
	 */
	private static String initFindEntitiesQuery(EntityKeyMetadata entityKeyMetadata) {
		StringBuilder queryBuilder = new StringBuilder( "MATCH " );
		queryBuilder.append( "(n:" );
		queryBuilder.append( ENTITY );
		queryBuilder.append( ":" );
		appendLabel( entityKeyMetadata, queryBuilder );
		queryBuilder.append( ") RETURN n" );
		return queryBuilder.toString();
	}

	/*
	 * Example:
	 *
	 * CREATE (n:ENTITY:table {id: {0}})
	 * RETURN n
	 */
	private static String initCreateEntityQuery(EntityKeyMetadata entityKeyMetadata) {
		StringBuilder queryBuilder = new StringBuilder( "CREATE " );
		appendEntityNode( "n", entityKeyMetadata, queryBuilder );
		queryBuilder.append( " RETURN n" );
		return queryBuilder.toString();
	}

	/*
	 * Example:
	 *
	 * MATCH (n:ENTITY:table {id: {0}}) --> (e:EMBEDDED)
	 * WITH e
	 * MATCH path=(e) -[*0..]-> (:EMBEDDED)
	 * FOREACH (r IN relationships(path) | DELETE r)
	 * FOREACH (e IN relationships(path) | DELETE e)
	 */
	private static String initRemoveEmbdeddedElementQuery(EntityKeyMetadata entityKeyMetadata) {
		StringBuilder queryBuilder = new StringBuilder( "MATCH " );
		appendEntityNode( "n", entityKeyMetadata, queryBuilder );
		queryBuilder.append( " --> (e:EMBEDDED)" );
		queryBuilder.append( " WITH e " );
		queryBuilder.append( " MATCH path=(e) -[*0..]-> (:EMBEDDED) ");
		queryBuilder.append( " FOREACH ( r IN relationships(path) | DELETE r )" );
		queryBuilder.append( " FOREACH ( e IN nodes(path) | DELETE e )" );
		return queryBuilder.toString();
	}

	/*
	 * Example:
	 *
	 * MATCH (n:ENTITY:table {id: {0}})
	 * OPTIONAL MATCH (n) - [r] - ()
	 * DELETE n, r
	 */
	private static String initRemoveEntityQuery(EntityKeyMetadata entityKeyMetadata) {
		StringBuilder queryBuilder = new StringBuilder( "MATCH " );
		appendEntityNode( "n", entityKeyMetadata, queryBuilder );
		queryBuilder.append( " OPTIONAL MATCH (n) - [r] - ()" );
		queryBuilder.append( " DELETE n, r" );
		return queryBuilder.toString();
	}

	/**
	 * Find the relationships representing the association.
	 *
	 * @param executionEngine the queries executor
	 * @param columnValues the values for the entity key column names of the owner node
	 * @param role the relationship type mapping the role of the association
	 * @return an iterator on the the results
	 */
	// We should move this in Neo4jAssociationQueries but, at the moment, having a query that only requires an EntityKeyMetadata make it easier
	// to deal with the *ToOne scenario
	public ResourceIterator<Relationship> findAssociation(GraphDatabaseService executionEngine, Object[] columnValues, String role) {
		String query = findAssociationQueryCache.get( role );
		if ( query == null ) {
			query = completeFindAssociationQuery( role );
			String cached = findAssociationQueryCache.putIfAbsent( role, query );
			if ( cached != null ) {
				query = cached;
			}
		}
		Map<String, Object> params = params( columnValues );
		executionEngine.execute( query, params );
		return executionEngine.execute( query, params( columnValues ) ).columnAs( "r" );
	}

	/**
	 * Create a single node representing an embedded element.
	 *
	 * @param executionEngine the {@link GraphDatabaseService} used to run the query
	 * @param columnValues the values in {@link org.hibernate.ogm.model.key.spi.EntityKey#getColumnValues()}
	 * @return the corresponding node;
	 */
	public Node createEmbedded(GraphDatabaseService executionEngine, Object[] columnValues) {
		Map<String, Object> params = params( columnValues );
		Result result = executionEngine.execute( createEmbeddedNodeQuery, params );
		return singleResult( result );
	}

	/**
	 * Find the node corresponding to an entity.
	 *
	 * @param executionEngine the {@link GraphDatabaseService} used to run the query
	 * @param columnValues the values in {@link org.hibernate.ogm.model.key.spi.EntityKey#getColumnValues()}
	 * @return the corresponding node
	 */
	public Node findEntity(GraphDatabaseService executionEngine, Object[] columnValues) {
		Map<String, Object> params = params( columnValues );
		Result result = executionEngine.execute( findEntityQuery, params );
		return singleResult( result );
	}

	/**
	 * Creates the node corresponding to an entity.
	 *
	 * @param executionEngine the {@link GraphDatabaseService} used to run the query
	 * @param columnValues the values in {@link org.hibernate.ogm.model.key.spi.EntityKey#getColumnValues()}
	 * @return the corresponding node
	 */
	public Node insertEntity(GraphDatabaseService executionEngine, Object[] columnValues) {
		Map<String, Object> params = params( columnValues );
		Result result = executionEngine.execute( createEntityQuery, params );
		return singleResult( result );
	}

	/**
	 * Find all the node representing the entity.
	 *
	 * @param executionEngine the {@link GraphDatabaseService} used to run the query
	 * @return an iterator over the nodes representing an entity
	 */
	public ResourceIterator<Node> findEntities(GraphDatabaseService executionEngine) {
		Result result = executionEngine.execute( findEntitiesQuery );
		return result.columnAs( "n" );
	}

	/**
	 * Remove the nodes representing the entity and the embedded elements attached to it.
	 *
	 * @param executionEngine the {@link GraphDatabaseService} used to run the query
	 * @param columnValues the values of the key identifying the entity to remove
	 */
	public void removeEntity(GraphDatabaseService executionEngine, Object[] columnValues) {
		Map<String, Object> params = params( columnValues );
		// Remove the embedded elements first
		executionEngine.execute( removeEmbdeddedElementQuery, params );
		// Remove the entity
		executionEngine.execute( removeEntityQuery, params );
	}

	/**
	 * Update the value of an embedded node property.
	 *
	 * @param executionEngine the {@link GraphDatabaseService} used to run the query
	 * @param keyValues the columns representing the identifier in the entity owning the embedded
	 * @param embeddedColumn the column on the embedded node (dot-separated properties)
	 * @param value the new value for the property
	 */
	public void updateEmbeddedColumn(GraphDatabaseService executionEngine, Object[] keyValues, String embeddedColumn, Object value) {
		String query = updateEmbeddedPropertyQueryCache.get( embeddedColumn );
		if ( query == null ) {
			query = initUpdateEmbeddedColumnQuery( keyValues, embeddedColumn );
			String cached = updateEmbeddedPropertyQueryCache.putIfAbsent( embeddedColumn, query );
			if ( cached != null ) {
				query = cached;
			}
		}
		Map<String, Object> params = params( ArrayHelper.concat( keyValues, value, value ) );
		executionEngine.execute( query, params );
	}

	/*
	 * Example:
	 *
	 * MERGE (owner:ENTITY:Account {login: {0}})
	 * MERGE (owner) - [:homeAddress] -> (e:EMBEDDED)
	 *   ON CREATE SET e.country = {1}
	 *   ON MATCH SET e.country = {2}
	 */
	private String initUpdateEmbeddedColumnQuery(Object[] keyValues, String embeddedColumn) {
		StringBuilder queryBuilder = new StringBuilder( updateEmbeddedNodeQuery );
		String[] columns = appendEmbeddedNodes( embeddedColumn, queryBuilder );
		queryBuilder.append( " ON CREATE SET e." );
		escapeIdentifier( queryBuilder, columns[columns.length - 1] );
		queryBuilder.append( " = {" );
		queryBuilder.append( keyValues.length );
		queryBuilder.append( "}" );
		queryBuilder.append( " ON MATCH SET e." );
		escapeIdentifier( queryBuilder, columns[columns.length - 1] );
		queryBuilder.append( " = {" );
		queryBuilder.append( keyValues.length + 1 );
		queryBuilder.append( "}" );
		return queryBuilder.toString();
	}

	/*
	 * Given an embedded properties path returns the cypher representation that can be appended to a MERGE or CREATE
	 * query.
	 */
	private static String[] appendEmbeddedNodes(String path, StringBuilder queryBuilder) {
		String[] columns = split( path );
		for ( int i = 0; i < columns.length - 1; i++ ) {
			queryBuilder.append( " - [:" );
			appendRelationshipType( queryBuilder, columns[i] );
			queryBuilder.append( "] ->" );
			if ( i < columns.length - 2 ) {
				queryBuilder.append( " (e" );
				queryBuilder.append( i );
				queryBuilder.append( ":" );
				queryBuilder.append( EMBEDDED );
				queryBuilder.append( ") MERGE (e" );
				queryBuilder.append( i );
				queryBuilder.append( ")" );
			}
		}
		queryBuilder.append( " (e:" );
		queryBuilder.append( EMBEDDED );
		queryBuilder.append( ")" );
		return columns;
	}
}
