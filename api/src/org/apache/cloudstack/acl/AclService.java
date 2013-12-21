// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.acl;

import java.util.List;

import org.apache.cloudstack.acl.AclPolicyPermission.Permission;

import com.cloud.user.Account;

public interface AclService {

    /* ACL group related interfaces */
    AclGroup createAclGroup(Account caller, String aclGroupName, String description);

    boolean deleteAclGroup(Long aclGroupId);

    List<AclGroup> listAclGroups(long accountId);

    AclGroup addAccountsToGroup(List<Long> acctIds, Long groupId);

    AclGroup removeAccountsFromGroup(List<Long> acctIds, Long groupId);

    /* ACL Policy related interfaces */
    AclPolicy createAclPolicy(Account caller, String aclPolicyName, String description, Long parentPolicyId);

    boolean deleteAclPolicy(long aclPolicyId);

    List<AclPolicy> listAclPolicies(long accountId);

    AclGroup attachAclPoliciesToGroup(List<Long> roleIds, Long groupId);

    AclGroup removeAclPoliciesFromGroup(List<Long> roleIds, Long groupId);

    AclPolicy addAclPermissionToAclPolicy(long aclPolicyId, String entityType, PermissionScope scope, Long scopeId, String action, Permission perm);

    AclPolicy removeAclPermissionFromAclPolicy(long aclPolicyId, String entityType, PermissionScope scope, Long scopeId, String action);

    AclPolicyPermission getAclPolicyPermission(long accountId, String entityType, String action);

    boolean isAPIAccessibleForPolicies(String apiName, List<AclPolicy> policies);

    List<AclPolicy> getEffectivePolicies(Account caller, ControlledEntity entity);

    /* Visibility related interfaces */
    boolean isGrantedAll(long accountId, String action);

    List<Long> getGrantedDomains(long accountId, String action);

    List<Long> getGrantedAccounts(long accountId, String action);

    List<Long> getGrantedResources(long accountId, String action);

}
