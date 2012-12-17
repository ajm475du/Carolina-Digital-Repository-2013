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
package edu.unc.lib.dl.data.ingest.solr.filter;

import java.util.ArrayList;
import java.util.Set;

import edu.unc.lib.dl.acl.service.AccessControlService;
import edu.unc.lib.dl.acl.util.ObjectAccessControlsBean;
import edu.unc.lib.dl.acl.util.Permission;
import edu.unc.lib.dl.acl.util.UserRole;
import edu.unc.lib.dl.data.ingest.solr.IndexingException;
import edu.unc.lib.dl.data.ingest.solr.indexing.DocumentIndexingPackage;

public class SetAccessControlFilter extends AbstractIndexDocumentFilter {

	private AccessControlService accessControlService;
	
	@Override
	public void filter(DocumentIndexingPackage dip) throws IndexingException {
		ObjectAccessControlsBean aclBean = accessControlService.getObjectAccessControls(dip.getPid());
		
		// Populate the list of groups with permission to retrieve records based on the view MD perm
		Set<String> readGroups = aclBean.getGroupsByPermission(Permission.viewDescription);
		// Merge in all groups with the list role assigned
		Set<String> listGroups = aclBean.getGroupsByUserRole(UserRole.list); 
		if (listGroups != null) {
			readGroups.addAll(listGroups);
		}
		
		if (readGroups.size() > 0)
			dip.getDocument().setReadGroup(new ArrayList<String>(readGroups));
		
		// Populate the list of groups that can view administrative aspects of the object
		Set<String> adminGroups = aclBean.getGroupsByPermission(Permission.viewAdminUI);
		if (adminGroups.size() > 0)
			dip.getDocument().setAdminGroup(new ArrayList<String>(adminGroups));
		
		// Add in flattened role group mappings
		dip.getDocument().setRoleGroup(aclBean.roleGroupsToList());
	}

	public void setAccessControlService(AccessControlService accessControlService) {
		this.accessControlService = accessControlService;
	}
}