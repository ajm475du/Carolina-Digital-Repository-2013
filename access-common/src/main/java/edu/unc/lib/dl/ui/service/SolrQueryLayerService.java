/**
 * Copyright 2008 The University of North Carolina at Chapel Hill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.unc.lib.dl.ui.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.lib.dl.search.solr.model.AbstractHierarchicalFacet;
import edu.unc.lib.dl.search.solr.model.BriefObjectMetadata;
import edu.unc.lib.dl.search.solr.model.BriefObjectMetadataBean;
import edu.unc.lib.dl.search.solr.model.CaseInsensitiveFacet;
import edu.unc.lib.dl.search.solr.model.CutoffFacet;
import edu.unc.lib.dl.search.solr.model.FacetFieldObject;
import edu.unc.lib.dl.search.solr.model.GenericFacet;
import edu.unc.lib.dl.search.solr.model.SearchRequest;
import edu.unc.lib.dl.search.solr.model.SearchResultResponse;
import edu.unc.lib.dl.search.solr.model.SearchState;
import edu.unc.lib.dl.search.solr.model.SimpleIdRequest;
import edu.unc.lib.dl.search.solr.service.SearchStateFactory;
import edu.unc.lib.dl.search.solr.service.SolrSearchService;
import edu.unc.lib.dl.search.solr.util.SearchFieldKeys;
import edu.unc.lib.dl.search.solr.util.SolrSettings;

import edu.unc.lib.dl.acl.util.AccessGroupSet;
import edu.unc.lib.dl.acl.exception.AccessRestrictionException;
import edu.unc.lib.dl.fedora.PID;
import edu.unc.lib.dl.search.solr.model.HierarchicalBrowseRequest;
import edu.unc.lib.dl.search.solr.model.HierarchicalBrowseResultResponse;
import edu.unc.lib.dl.ui.exception.ResourceNotFoundException;
import edu.unc.lib.dl.ui.util.AccessUtil;
import edu.unc.lib.dl.util.ContentModelHelper;

/**
 * Solr query construction layer. Constructs search states specific to common tasks before passing them on to lower
 * level classes to retrieve the results.
 * 
 * @author bbpennel
 */
public class SolrQueryLayerService extends SolrSearchService {
	private static final Logger LOG = LoggerFactory.getLogger(SolrQueryLayerService.class);
	protected SearchStateFactory searchStateFactory;
	protected PID collectionsPid;

	/**
	 * Returns a list of the most recently added items in the collection
	 * 
	 * @param accessGroups
	 * @return Result response, where items only contain title and id.
	 */
	public SearchResultResponse getNewlyAdded(AccessGroupSet accessGroups) {
		SearchRequest searchRequest = new SearchRequest();
		searchRequest.setAccessGroups(accessGroups);

		SearchState searchState = searchStateFactory.createTitleListSearchState();
		List<String> resourceTypes = new ArrayList<String>();
		resourceTypes.add(searchSettings.resourceTypeCollection);
		searchState.setResourceTypes(resourceTypes);
		searchState.setRowsPerPage(searchSettings.defaultListResultsPerPage);
		searchState.setSortType("dateAdded");

		searchRequest.setSearchState(searchState);
		return getSearchResults(searchRequest);
	}

	/**
	 * Returns a list of collections
	 * 
	 * @param accessGroups
	 * @return
	 */
	public SearchResultResponse getCollectionList(AccessGroupSet accessGroups) {
		SearchRequest searchRequest = new SearchRequest();
		searchRequest.setAccessGroups(accessGroups);

		SearchState searchState = searchStateFactory.createSearchState();
		searchState.setResourceTypes(searchSettings.defaultCollectionResourceTypes);
		searchState.setRowsPerPage(50);
		searchState.setFacetsToRetrieve(null);
		ArrayList<String> resultFields = new ArrayList<String>();
		resultFields.add(SearchFieldKeys.ANCESTOR_PATH.name());
		resultFields.add(SearchFieldKeys.TITLE.name());
		resultFields.add(SearchFieldKeys.ID.name());
		searchState.setResultFields(resultFields);

		searchRequest.setSearchState(searchState);
		return getSearchResults(searchRequest);
	}

	public FacetFieldObject getDepartmentList(AccessGroupSet accessGroups) {
		SearchState searchState = searchStateFactory.createFacetSearchState(SearchFieldKeys.DEPARTMENT.name(), "index",
				Integer.MAX_VALUE);

		SearchRequest searchRequest = new SearchRequest(searchState, accessGroups);

		SearchResultResponse results = getSearchResults(searchRequest);

		if (results.getFacetFields() != null && results.getFacetFields().size() > 0) {
			FacetFieldObject deptField = results.getFacetFields().get(0);
			if (deptField != null) {
				CaseInsensitiveFacet.deduplicateCaseInsensitiveValues(deptField);
			}
			return deptField;
		}
		return null;
	}

	/**
	 * Retrieves the facet list for the search defined by searchState. The facet results optionally can ignore
	 * hierarchical cutoffs.
	 * 
	 * @param searchState
	 * @param facetsToRetrieve
	 * @param applyCutoffs
	 * @return
	 */
	public SearchResultResponse getFacetList(SearchState baseState, AccessGroupSet accessGroups,
			List<String> facetsToRetrieve, boolean applyCutoffs) {
		SearchState searchState = (SearchState) baseState.clone();

		CutoffFacet ancestorPath;
		LOG.debug("Retrieving facet list");
		if (!searchState.getFacets().containsKey(SearchFieldKeys.ANCESTOR_PATH.name())) {
			ancestorPath = new CutoffFacet(SearchFieldKeys.ANCESTOR_PATH.name(), "2,*");
			ancestorPath.setFacetCutoff(3);
			searchState.getFacets().put(SearchFieldKeys.ANCESTOR_PATH.name(), ancestorPath);
		} else {
			ancestorPath = (CutoffFacet) searchState.getFacets().get(SearchFieldKeys.ANCESTOR_PATH.name());
			if (ancestorPath.getFacetCutoff() == null)
				ancestorPath.setFacetCutoff(ancestorPath.getHighestTier() + 1);
		}

		if (!applyCutoffs) {
			ancestorPath.setCutoff(null);
		}
		
		// Turning off rollup because it is really slow
		searchState.setRollup(false);

		SearchRequest searchRequest = new SearchRequest();
		searchRequest.setAccessGroups(accessGroups);
		searchRequest.setSearchState(searchState);

		searchState.setRowsPerPage(0);
		if (facetsToRetrieve != null)
			searchState.setFacetsToRetrieve(facetsToRetrieve);
		searchState.setResourceTypes(null);

		SearchResultResponse resultResponse = getSearchResults(searchRequest);

		// If this facet list contains parent collections, then get further metadata about them
		if (resultResponse.getFacetFields() != null
				&& (searchState.getFacetsToRetrieve() == null || searchState.getFacetsToRetrieve().contains(
						SearchFieldKeys.PARENT_COLLECTION.name()))) {
			FacetFieldObject parentCollectionFacet = resultResponse.getFacetFields().get(
					SearchFieldKeys.PARENT_COLLECTION.name());
			List<BriefObjectMetadataBean> parentCollectionValues = getParentCollectionValues(resultResponse
					.getFacetFields().get(SearchFieldKeys.PARENT_COLLECTION.name()), accessGroups);
			int i;
			// If the parent collection facet yielded further metadata, then edit the original facet value to contain the
			// additional metadata
			if (parentCollectionFacet != null && parentCollectionValues != null) {
				for (GenericFacet pidFacet : parentCollectionFacet.getValues()) {
					String pid = pidFacet.getSearchValue();
					for (i = 0; i < parentCollectionValues.size() && !pid.equals(parentCollectionValues.get(i).getId()); i++)
						;
					if (i < parentCollectionValues.size()) {
						CutoffFacet parentPath = parentCollectionValues.get(i).getPath();
						pidFacet.setFieldName(SearchFieldKeys.ANCESTOR_PATH.name());
						pidFacet.setDisplayValue(parentPath.getDisplayValue());
						pidFacet.setValue(parentPath.getSearchValue());
					}
				}
			}
		}

		return resultResponse;
	}

	/**
	 * Retrieves metadata fields for the parent collection pids contained by the supplied facet object.
	 * 
	 * @param parentCollectionFacet
	 *           Facet object containing parent collection ids to lookup
	 * @param accessGroups
	 * @return
	 */
	public List<BriefObjectMetadataBean> getParentCollectionValues(FacetFieldObject parentCollectionFacet,
			AccessGroupSet accessGroups) {
		if (parentCollectionFacet == null || parentCollectionFacet.getValues() == null
				|| parentCollectionFacet.getValues().size() == 0) {
			return null;
		}

		QueryResponse queryResponse = null;
		SolrQuery solrQuery = new SolrQuery();
		StringBuilder query = new StringBuilder();
		boolean first = true;

		query.append('(');
		for (GenericFacet pidFacet : parentCollectionFacet.getValues()) {
			if (pidFacet.getSearchValue() != null && pidFacet.getSearchValue().length() > 0) {
				if (first) {
					first = false;
				} else {
					query.append(" OR ");
				}
				query.append(solrSettings.getFieldName(SearchFieldKeys.ID.name())).append(':')
						.append(SolrSettings.sanitize(pidFacet.getSearchValue()));
			}
		}
		query.append(')');

		// If no pids were added to the query, then there's nothing to look up
		if (first) {
			return null;
		}

		try {
			// Add access restrictions to query
			addAccessRestrictions(query, accessGroups);
		} catch (AccessRestrictionException e) {
			// If the user doesn't have any access groups, they don't have access to anything, return null.
			LOG.error("No access groups", e);
			return null;
		}

		solrQuery.setQuery(query.toString());

		solrQuery.setFacet(true);
		solrQuery.setFields(solrSettings.getFieldName(SearchFieldKeys.ID.name()),
				solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_PATH.name()),
				solrSettings.getFieldName(SearchFieldKeys.TITLE.name()));

		solrQuery.setRows(parentCollectionFacet.getValues().size());

		try {
			queryResponse = this.executeQuery(solrQuery);
			return queryResponse.getBeans(BriefObjectMetadataBean.class);
		} catch (SolrServerException e) {
			LOG.error("Failed to execute query " + solrQuery.toString(), e);
		}
		return null;
	}

	/**
	 * Retrieves a list of the nearest windowSize neighbors within the nearest parent collection or folder around the
	 * item metadata, based on the order field of the item. The first windowSize/2 neighbors are retrieved to each side
	 * of the item. If there are fewer than windowSize/2 items to a side, then the opposite side of the window is
	 * expanded by the difference so that the total number of records will equal windowSize if there are enough total
	 * records in the parent container.
	 * 
	 * @param metadata
	 *           Record which the window pivots around.
	 * @param windowSize
	 *           max number of items in the window. This includes the pivot, so odd numbers are recommended.
	 * @param accessGroups
	 *           Access groups of the user making this request.
	 * @return
	 */
	public List<BriefObjectMetadataBean> getNeighboringItems(BriefObjectMetadataBean metadata, int windowSize,
			AccessGroupSet accessGroups) {
		int splitSize = windowSize / 2;
		int rows = splitSize;
		List<BriefObjectMetadataBean> leftList = null;
		List<BriefObjectMetadataBean> rightList = null;

		Long pivotOrder = metadata.getDisplayOrder();
		if (pivotOrder == null)
			pivotOrder = (long) 0;

		StringBuilder accessQuery = new StringBuilder("*:*");
		try {
			// Add access restrictions to query
			addAccessRestrictions(accessQuery, accessGroups);
		} catch (AccessRestrictionException e) {
			// If the user doesn't have any access groups, they don't have access to anything, return null.
			LOG.error(e.getMessage());
			return null;
		}

		QueryResponse queryResponse = null;
		SolrQuery solrQuery = new SolrQuery();
		StringBuilder query = new StringBuilder();

		// Get the first half of the window to the left of the pivot record
		query.append(solrSettings.getFieldName(SearchFieldKeys.DISPLAY_ORDER.name())).append(":[* TO ");
		if (pivotOrder == 0)
			query.append(0);
		else
			query.append(pivotOrder - 1);
		query.append(']');

		query.append(accessQuery);
		solrQuery.setQuery(query.toString());

		solrQuery.setFacet(true);
		solrQuery.addFilterQuery(solrSettings.getFieldName(SearchFieldKeys.RESOURCE_TYPE.name()) + ":File");
		CutoffFacet ancestorPath = null;
		if (metadata.getResourceType().equals(searchSettings.resourceTypeFile)) {
			ancestorPath = metadata.getAncestorPathFacet();
		} else {
			ancestorPath = metadata.getPath();
		}
		if (ancestorPath != null) {
			facetFieldUtil.addDefaultFacetPivot(ancestorPath, solrQuery);
		}

		solrQuery.setStart(0);
		solrQuery.setRows(rows);

		solrQuery.setSortField(solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_NAMES.name()), SolrQuery.ORDER.asc);
		solrQuery.addSortField(solrSettings.getFieldName(SearchFieldKeys.DISPLAY_ORDER.name()), SolrQuery.ORDER.desc);

		// Execute left hand query
		try {
			queryResponse = this.executeQuery(solrQuery);
			leftList = queryResponse.getBeans(BriefObjectMetadataBean.class);
		} catch (SolrServerException e) {
			LOG.error("Error retrieving Neighboring items: " + e);
			return null;
		}

		// Expand the right side window size by the difference between the split size and the left window result count.
		if (leftList.size() < splitSize) {
			rows = splitSize * 2 - leftList.size();
		}

		query = new StringBuilder();
		// Get the right half of the window where display order is greater than the pivot
		query.append(solrSettings.getFieldName(SearchFieldKeys.DISPLAY_ORDER.name())).append(":[").append(pivotOrder + 1)
				.append(" TO *]");
		query.append(accessQuery);
		solrQuery.setQuery(query.toString());

		solrQuery.setRows(rows);

		solrQuery.setSortField(solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_NAMES.name()), SolrQuery.ORDER.asc);
		solrQuery.addSortField(solrSettings.getFieldName(SearchFieldKeys.DISPLAY_ORDER.name()), SolrQuery.ORDER.asc);

		// Execute right hand query
		try {
			queryResponse = this.executeQuery(solrQuery);
			rightList = queryResponse.getBeans(BriefObjectMetadataBean.class);
		} catch (SolrServerException e) {
			LOG.error("Error retrieving Neighboring items: " + e);
			return null;
		}

		// If there are no more items to the left or the left and right windows plus pivot equals the window size, then
		// we're done.
		if (leftList.size() < splitSize || rightList.size() + leftList.size() + 1 == windowSize) {
			Collections.reverse(leftList);
			leftList.add(metadata);
			leftList.addAll(rightList);
			return leftList;
		}

		// Less than split size from the right side but not touching the left side, so we need to expand the left side
		query.append(solrSettings.getFieldName(SearchFieldKeys.DISPLAY_ORDER.name())).append(":[* TO ")
				.append(leftList.get(leftList.size() - 1).getDisplayOrder() - 1).append(']');
		query.append(accessQuery);
		solrQuery.setQuery(query.toString());

		solrQuery.setRows(splitSize * 2 - leftList.size());

		solrQuery.setSortField(solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_NAMES.name()), SolrQuery.ORDER.asc);
		solrQuery.addSortField(solrSettings.getFieldName(SearchFieldKeys.DISPLAY_ORDER.name()), SolrQuery.ORDER.desc);

		// Execute query and add the results to the end of the left list
		try {
			queryResponse = this.executeQuery(solrQuery);
			leftList.addAll(queryResponse.getBeans(BriefObjectMetadataBean.class));
		} catch (SolrServerException e) {
			LOG.error("Error retrieving Neighboring items: " + e);
			return null;
		}

		Collections.reverse(leftList);
		leftList.add(metadata);
		leftList.addAll(rightList);
		return leftList;
	}

	/**
	 * Returns the number of children plus a facet list for the parent defined by ancestorPath.
	 * 
	 * @param ancestorPath
	 * @param accessGroups
	 * @return
	 */
	public SearchResultResponse getFullRecordSupplementalData(CutoffFacet ancestorPath, AccessGroupSet accessGroups,
			List<String> facetsToRetrieve) {
		SearchState searchState = searchStateFactory.createSearchState();
		searchState.getFacets().put(SearchFieldKeys.ANCESTOR_PATH.name(), ancestorPath);
		searchState.setRowsPerPage(0);
		return getFacetList(searchState, accessGroups, facetsToRetrieve, false);
	}

	public long getChildrenCount(BriefObjectMetadataBean metadataObject, AccessGroupSet accessGroups) {
		QueryResponse queryResponse = null;
		SolrQuery solrQuery = new SolrQuery();
		StringBuilder query = new StringBuilder("*:* ");

		try {
			// Add access restrictions to query
			addAccessRestrictions(query, accessGroups);
		} catch (AccessRestrictionException e) {
			// If the user doesn't have any access groups, they don't have access to anything, return null.
			LOG.error(e.getMessage());
			return -1;
		}

		solrQuery.setStart(0);
		solrQuery.setRows(0);

		solrQuery.setQuery(query.toString());

		query = new StringBuilder();
		query.append(solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_PATH.name())).append(':')
				.append(SolrSettings.sanitize(metadataObject.getPath().getSearchValue())).append(",*");

		solrQuery.setFacet(true);
		solrQuery.addFilterQuery(query.toString());

		try {
			queryResponse = this.executeQuery(solrQuery);
			return queryResponse.getResults().getNumFound();
		} catch (SolrServerException e) {
			LOG.error("Error retrieving Solr search result request: " + e);
		}
		return -1;
	}

	/**
	 * Populates the child count attributes of all metadata objects in the given search result response by querying for
	 * all non-folder objects which have the metadata object's highest ancestor path tier somewhere in its ancestor path.
	 * 
	 * @param resultResponse
	 * @param accessGroups
	 */
	public long getChildrenCounts(List<BriefObjectMetadata> resultList, AccessGroupSet accessGroups) {
		return this.getChildrenCounts(resultList, accessGroups, "child", null, null);
	}

	public long getChildrenCounts(List<BriefObjectMetadata> resultList, AccessGroupSet accessGroups, String countName,
			String queryAddendum, SolrQuery baseQuery) {
		if (resultList == null || resultList.size() == 0)
			return 0;

		String ancestorPathField = solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_PATH.name());
		long maxTier = 0;
		boolean first = true;
		StringBuilder query;

		QueryResponse queryResponse = null;
		SolrQuery solrQuery;
		if (baseQuery == null) {
			// Create a base query since we didn't receive one
			solrQuery = new SolrQuery();

			query = new StringBuilder("*:*");

			try {
				// Add access restrictions to query
				addAccessRestrictions(query, accessGroups);
			} catch (AccessRestrictionException e) {
				// If the user doesn't have any access groups, they don't have access to anything, return null.
				LOG.error(e.getMessage());
				return 0;
			}

			solrQuery.setStart(0);
			solrQuery.setRows(0);

			solrQuery.setQuery(query.toString());
		} else {
			solrQuery = baseQuery;
			// Remove all ancestor path related filter queries so the counts won't be cut off
			for (String filterQuery : solrQuery.getFilterQueries()) {
				if (filterQuery.contains(ancestorPathField)) {
					solrQuery.removeFilterQuery(filterQuery);
				}
			}
		}

		if (queryAddendum != null) {
			solrQuery.setQuery(solrQuery.getQuery() + " AND " + queryAddendum);
		}

		query = new StringBuilder();

		query.append(solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_PATH.name())).append(':');
		List<BriefObjectMetadata> containerObjects = new ArrayList<BriefObjectMetadata>();
		for (BriefObjectMetadata metadataObject : resultList) {
			if (metadataObject.getPath() != null
					&& metadataObject.getContentModel().contains(ContentModelHelper.Model.CONTAINER.toString())) {
				if (first) {
					first = false;
					query.append("(");
				} else {
					query.append(" OR ");
				}
				query.append(SolrSettings.sanitize(metadataObject.getPath().getSearchValue())).append(",*");
				containerObjects.add(metadataObject);
				long highestTier = metadataObject.getPath().getHighestTier();
				if (maxTier < highestTier)
					maxTier = highestTier;
			}
		}

		// If there weren't any container entries in the results, then nothing to retrieve, lets get out of here
		if (first) {
			return 0;
		}

		query.append(")");

		// Make sure that the query isn't too big for solr to accept
		if (query.length() < 10000)
			solrQuery.addFilterQuery(query.toString());

		try {
			solrQuery.setFacet(true);
			solrQuery.setFacetMinCount(1);
			solrQuery.addFacetField(solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_PATH.name()));

			// Retrieve as many ancestor paths as we can get
			solrQuery.add("f." + solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_PATH.name()) + ".facet.limit",
					String.valueOf(Integer.MAX_VALUE));

			// Don't return any facets past the max tier in the contain set, but don't filter to this since that'd effect
			// counts
			StringBuilder facetQuery = new StringBuilder();
			facetQuery.append('!').append(solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_PATH.name())).append(':')
					.append(maxTier + 1).append(searchSettings.facetSubfieldDelimiter).append('*');
			solrQuery.addFacetQuery(facetQuery.toString());

			queryResponse = this.executeQuery(solrQuery);
			assignChildrenCounts(queryResponse, containerObjects, countName);
			return queryResponse.getResults().getNumFound();
		} catch (SolrServerException e) {
			LOG.error("Error retrieving Solr search result request: " + e);
		}
		return 0;
	}

	protected void assignChildrenCounts(QueryResponse queryResponse, List<BriefObjectMetadata> containerObjects,
			String countName) {
		for (FacetField facetField : queryResponse.getFacetFields()) {
			if (facetField.getValues() != null) {
				for (FacetField.Count facetValue : facetField.getValues()) {
					for (int i = 0; i < containerObjects.size(); i++) {
						BriefObjectMetadata container = containerObjects.get(i);
						if (facetValue.getName().indexOf(container.getPath().getSearchValue()) == 0) {
							container.getCountMap().put(countName, facetValue.getCount());
							break;
						}
					}
				}
			}
		}
	}

	/**
	 * Retrieves results for populating a hierarchical browse view. Supports all the regular navigation available to
	 * searches. Results contain child counts for each item (all items returned are containers), and a map containing the
	 * number of nested subcontainers per container. Children counts are retrieved based on facet counts.
	 * 
	 * @param browseRequest
	 * @return
	 */
	public HierarchicalBrowseResultResponse getHierarchicalBrowseResults(HierarchicalBrowseRequest browseRequest) {
		AccessGroupSet accessGroups = browseRequest.getAccessGroups();
		SearchState browseState = (SearchState) browseRequest.getSearchState().clone();

		boolean noRootNode = !browseState.getFacets().containsKey(SearchFieldKeys.ANCESTOR_PATH.name());
		// Default the ancestor path to the collections object so we always have a root
		if (noRootNode) {
			browseState.getFacets().put(SearchFieldKeys.ANCESTOR_PATH.name(),
					new CutoffFacet(SearchFieldKeys.ANCESTOR_PATH.name(), "1," + this.collectionsPid.getPid()));
		}

		HierarchicalBrowseResultResponse browseResults = new HierarchicalBrowseResultResponse();
		SearchState hierarchyState = searchStateFactory.createHierarchyListSearchState();
		// Use the ancestor path facet from the state where we will have set a default value
		hierarchyState.getFacets().put(SearchFieldKeys.ANCESTOR_PATH.name(),
				browseState.getFacets().get(SearchFieldKeys.ANCESTOR_PATH.name()));

		hierarchyState.setRowsPerPage(0);

		SearchRequest hierarchyRequest = new SearchRequest(hierarchyState, accessGroups);

		SolrQuery baseQuery = this.generateSearch(hierarchyRequest, false);
		// Get the set of all applicable containers
		SolrQuery hierarchyQuery = baseQuery.getCopy();
		hierarchyQuery.setRows(Integer.MAX_VALUE);

		// Reusable query segment for limiting the results to just the depth asked for
		StringBuilder cutoffQuery = new StringBuilder();
		cutoffQuery.append('!').append(solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_PATH.name())).append(":");
		cutoffQuery.append(((CutoffFacet) hierarchyState.getFacets().get(SearchFieldKeys.ANCESTOR_PATH.name()))
				.getHighestTier() + browseRequest.getRetrievalDepth());
		cutoffQuery.append(searchSettings.facetSubfieldDelimiter).append('*');
		hierarchyQuery.addFilterQuery(cutoffQuery.toString());

		SearchResultResponse results;
		try {
			results = this.executeSearch(hierarchyQuery, hierarchyState, false, false);
			browseResults.setSearchResultResponse(results);
		} catch (SolrServerException e) {
			LOG.error("Error while getting container results for hierarchical browse results", e);
			return null;
		}
		// Get the root node for this search so that it can be displayed as the top tier
		BriefObjectMetadataBean rootNode = getObjectById(new SimpleIdRequest(((CutoffFacet) browseState.getFacets().get(
				SearchFieldKeys.ANCESTOR_PATH.name())).getSearchKey(), browseRequest.getAccessGroups()));
		if (rootNode == null) {
			throw new ResourceNotFoundException();
		}
		browseResults.getResultList().add(0, rootNode);

		if (results.getResultCount() > 0) {
			// Get the children counts per container
			SearchRequest filteredChildrenRequest = new SearchRequest(browseState, browseRequest.getAccessGroups());
			browseResults.setRootCount(this.getChildrenCounts(results.getResultList(), accessGroups, "child", null,
					this.generateSearch(filteredChildrenRequest, true)));

			try {
				// Add in the sub-container counts per container for indentation purposes
				browseResults.populateSubcontainerCounts(getSubcontainerCounts(
						((CutoffFacet) browseState.getFacets().get(SearchFieldKeys.ANCESTOR_PATH.name())),
						browseRequest.getRetrievalDepth(), baseQuery));

				// If anything that constituted a search is in the request then trim out possible empty folders
				if (browseState.getFacets().size() > 1 || browseState.getRangeFields().size() > 0
						|| browseState.getSearchFields().size() > 0 || browseState.getAccessTypeFilter() != null) {
					// Get the list of any direct matches for the current query
					browseResults.setMatchingContainerPids(this.getDirectContainerMatches(browseState, accessGroups));
					// Remove all containers that are not direct matches for the user's query and have 0 children  
					browseResults.removeContainersWithoutContents();
				}
			} catch (SolrServerException e) {
				LOG.error("Error while getting children counts for hierarchical browse", e);
				return null;
			}
		}

		// Retrieve normal item search results, which are restricted to a max number per page
		if (browseState.getRowsPerPage() > 0
				&& (browseState.getResourceTypes() == null || browseState.getResourceTypes().contains(
						searchSettings.resourceTypeFile))) {
			SearchState fileSearchState = new SearchState(browseState);
			List<String> resourceTypes = new ArrayList<String>();
			resourceTypes.add(searchSettings.resourceTypeFile);
			fileSearchState.setResourceTypes(resourceTypes);
			Object ancestorPath = fileSearchState.getFacets().get(SearchFieldKeys.ANCESTOR_PATH.name());
			if (ancestorPath instanceof CutoffFacet) {
				((CutoffFacet) ancestorPath).setCutoff(((CutoffFacet) ancestorPath).getHighestTier() + 1);
			}
			fileSearchState.setFacetsToRetrieve(null);
			SearchRequest fileSearchRequest = new SearchRequest(fileSearchState, browseRequest.getAccessGroups());
			SearchResultResponse fileResults = this.getSearchResults(fileSearchRequest);
			browseResults.populateItemResults(fileResults.getResultList());
		}

		return browseResults;
	}

	private List<FacetField> getSubcontainerCounts(CutoffFacet ancestorPath, int retrievalDepth, SolrQuery baseQuery)
			throws SolrServerException {
		SolrQuery subcontainerQuery = baseQuery.getCopy();

		// Limit the results to one tier past the depth being retrieved, since ancestor path does not include the
		// container itself, and going only to the depth requested would get the counts for the previous tier
		StringBuilder cutoffNextTierQuery = new StringBuilder();
		cutoffNextTierQuery.append('!').append(solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_PATH.name()))
				.append(":");
		if (ancestorPath != null) {
			LOG.debug("Restricting subcontainer counts to " + ancestorPath.getHighestTier() + " + " + (retrievalDepth + 1));
			cutoffNextTierQuery.append(ancestorPath.getHighestTier() + retrievalDepth);
		} else {
			LOG.debug("Restricting subcontainer counts to " + (retrievalDepth));

			cutoffNextTierQuery.append(retrievalDepth + 1);
		}
		cutoffNextTierQuery.append(searchSettings.facetSubfieldDelimiter).append('*');
		subcontainerQuery.addFilterQuery(cutoffNextTierQuery.toString());

		subcontainerQuery.setRows(0);

		subcontainerQuery.setFacet(true);
		subcontainerQuery.setFacetMinCount(1);
		subcontainerQuery.addFacetField(solrSettings.getFieldName(SearchFieldKeys.ANCESTOR_PATH.name()));

		subcontainerQuery.setFacetLimit(-1);

		QueryResponse queryResponse = this.executeQuery(subcontainerQuery);
		return queryResponse.getFacetFields();
	}

	/**
	 * Returns a set of object IDs for containers that directly matched the restrictions from the base query.
	 * 
	 * @param baseState
	 * @param accessGroups
	 * @return
	 * @throws SolrServerException
	 */
	private Set<String> getDirectContainerMatches(SearchState baseState, AccessGroupSet accessGroups)
			throws SolrServerException {
		SearchState directMatchState = (SearchState) baseState.clone();
		directMatchState.setResourceTypes(null);
		directMatchState.setResultFields(Arrays.asList(SearchFieldKeys.ID.name()));
		directMatchState.getFacets().put(SearchFieldKeys.CONTENT_MODEL.name(),
				ContentModelHelper.Model.CONTAINER.toString());
		directMatchState.setRowsPerPage(Integer.MAX_VALUE);
		SearchRequest directMatchRequest = new SearchRequest(directMatchState, accessGroups);
		SolrQuery directMatchQuery = this.generateSearch(directMatchRequest, false);
		QueryResponse directMatchResponse = this.executeQuery(directMatchQuery);
		String idField = solrSettings.getFieldName(SearchFieldKeys.ID.name());
		Set<String> directMatchIds = new HashSet<String>(directMatchResponse.getResults().size());
		for (SolrDocument document : directMatchResponse.getResults()) {
			directMatchIds.add((String) document.getFirstValue(idField));
		}
		return directMatchIds;
	}

	public SearchResultResponse getHierarchicalBrowseItemResult(HierarchicalBrowseRequest browseRequest) {
		SearchState fileSearchState = new SearchState(browseRequest.getSearchState());
		List<String> resourceTypes = new ArrayList<String>();
		resourceTypes.add(searchSettings.resourceTypeFile);
		fileSearchState.setResourceTypes(resourceTypes);
		Object ancestorPath = fileSearchState.getFacets().get(SearchFieldKeys.ANCESTOR_PATH.name());
		if (ancestorPath instanceof CutoffFacet) {
			((CutoffFacet) ancestorPath).setCutoff(((CutoffFacet) ancestorPath).getHighestTier() + 1);
		}
		fileSearchState.setFacetsToRetrieve(null);
		SearchRequest fileSearchRequest = new SearchRequest(fileSearchState, browseRequest.getAccessGroups());
		SearchResultResponse fileResults = this.getSearchResults(fileSearchRequest);
		return fileResults;
	}

	/**
	 * Matches hierarchical facets in the search state with those in the facet list. If a match is found, then the search
	 * state hierarchical facet is overwritten with the result facet in order to give it a display value.
	 * 
	 * @param searchState
	 * @param resultResponse
	 */
	public void lookupHierarchicalDisplayValues(SearchState searchState, AccessGroupSet accessGroups) {
		if (searchState.getFacets() == null)
			return;
		Iterator<String> facetIt = searchState.getFacets().keySet().iterator();
		while (facetIt.hasNext()) {
			String facetKey = facetIt.next();
			Object facetValue = searchState.getFacets().get(facetKey);
			if (facetValue instanceof AbstractHierarchicalFacet) {
				FacetFieldObject resultFacet = getHierarchicalFacet((AbstractHierarchicalFacet) facetValue, accessGroups);
				if (resultFacet != null) {
					GenericFacet facet = resultFacet.getValues().get(resultFacet.getValues().size() - 1);
					searchState.getFacets().put(facetKey, facet);
					if (facetValue instanceof CutoffFacet) {
						((CutoffFacet) facet).setCutoff(((CutoffFacet) facetValue).getCutoff());
					}
				}
			}
		}
	}

	/**
	 * Checks if an item is accessible given the specified access restrictions
	 * 
	 * @param idRequest
	 * @param accessType
	 * @return
	 */
	public boolean isAccessible(SimpleIdRequest idRequest) {
		QueryResponse queryResponse = null;
		SolrQuery solrQuery = new SolrQuery();
		StringBuilder query = new StringBuilder();

		PID pid = new PID(idRequest.getId());
		String id = pid.getPid();
		String[] idParts = id.split("/");
		String datastream = null;
		if (idParts.length > 1) {
			id = idParts[0];
			datastream = idParts[1];
			solrQuery.addField(solrSettings.getFieldName(SearchFieldKeys.ROLE_GROUP.name()));
		}

		query.append(solrSettings.getFieldName(SearchFieldKeys.ID.name())).append(':').append(SolrSettings.sanitize(id));

		try {
			// Add access restrictions to query
			addAccessRestrictions(query, idRequest.getAccessGroups());
		} catch (AccessRestrictionException e) {
			// If the user doesn't have any access groups, they don't have access to anything, return null.
			LOG.error(e.getMessage());
			return false;
		}

		solrQuery.setQuery(query.toString());
		if (datastream == null)
			solrQuery.setRows(0);
		else
			solrQuery.setRows(1);

		solrQuery.addField(solrSettings.getFieldName(SearchFieldKeys.ID.name()));

		LOG.debug("getObjectById query: " + solrQuery.toString());
		try {
			queryResponse = this.executeQuery(solrQuery);
			if (queryResponse.getResults().getNumFound() == 0)
				return false;
			if (datastream == null)
				return true;

			List<BriefObjectMetadataBean> results = queryResponse.getBeans(BriefObjectMetadataBean.class);
			BriefObjectMetadataBean metadata = results.get(0);

			return AccessUtil.permitDatastreamAccess(idRequest.getAccessGroups(), datastream, metadata);
		} catch (SolrServerException e) {
			LOG.error("Error retrieving Solr object request: " + e);
		}

		return false;
	}

	public void setSearchStateFactory(SearchStateFactory searchStateFactory) {
		this.searchStateFactory = searchStateFactory;
	}

	public void setCollectionsPid(PID collectionsPid) {
		this.collectionsPid = collectionsPid;
	}
}
