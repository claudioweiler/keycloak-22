/*
 * Copyright 2023 Red Hat, Inc. and/or its affiliates
 *  and other contributors as indicated by the @author tags.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.keycloak.testsuite.federation.ldap;

import java.io.IOException;
import java.util.Set;

import org.jboss.arquillian.graphene.page.Page;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.common.Profile;
import org.keycloak.models.LDAPConstants;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserProfileAttributeMetadata;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.ldap.idm.model.LDAPObject;
import org.keycloak.testsuite.ProfileAssume;
import org.keycloak.testsuite.admin.ApiUtil;
import org.keycloak.testsuite.arquillian.annotation.EnableFeature;
import org.keycloak.testsuite.forms.VerifyProfileTest;
import org.keycloak.testsuite.pages.LoginUpdateProfilePage;
import org.keycloak.testsuite.util.LDAPRule;
import org.keycloak.testsuite.util.LDAPTestUtils;
import org.keycloak.userprofile.config.UPConfigUtils;

import static org.keycloak.storage.UserStorageProviderModel.IMPORT_ENABLED;
import static org.keycloak.userprofile.AbstractUserProfileProvider.USER_METADATA_GROUP;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@EnableFeature(value = Profile.Feature.DECLARATIVE_USER_PROFILE)
public class LDAPUserProfileTest extends AbstractLDAPTest {

    @ClassRule
    public static LDAPRule ldapRule = new LDAPRule();

    @Page
    protected LoginUpdateProfilePage updateProfilePage;

    @Override
    protected LDAPRule getLDAPRule() {
        return ldapRule;
    }

    @Before
    public void before() {
        // don't run this test when map storage is enabled, as map storage doesn't support LDAP, yet
        ProfileAssume.assumeFeatureDisabled(Profile.Feature.MAP_STORAGE);
    }

    @Override
    protected void afterImportTestRealm() {
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            UserModel user = LDAPTestUtils.addLocalUser(session, appRealm, "marykeycloak", "mary@test.com", "Password1");
            user.setFirstName("Mary");
            user.setLastName("Kelly");

            LDAPTestUtils.addZipCodeLDAPMapper(appRealm, ctx.getLdapModel());

            // Delete all LDAP users and add some new for testing
            LDAPTestUtils.removeAllLDAPUsers(ctx.getLdapProvider(), appRealm);

            LDAPObject john = LDAPTestUtils.addLDAPUser(ctx.getLdapProvider(), appRealm, "johnkeycloak", "John", "Doe", "john@email.org", null, "1234");
            LDAPTestUtils.updateLDAPPassword(ctx.getLdapProvider(), john, "Password1");

            LDAPObject john2 = LDAPTestUtils.addLDAPUser(ctx.getLdapProvider(), appRealm, "johnkeycloak2", "John", "Doe", "john2@email.org", null, "1234");
            LDAPTestUtils.updateLDAPPassword(ctx.getLdapProvider(), john2, "Password1");
        });

        RealmRepresentation realm = testRealm().toRepresentation();
        VerifyProfileTest.enableDynamicUserProfile(realm);
        testRealm().update(realm);
    }

    @Test
    public void testUserProfile() {
        // Test user profile of user johnkeycloak in admin API
        UserResource johnResource = ApiUtil.findUserByUsernameId(testRealm(), "johnkeycloak");
        UserRepresentation john = johnResource.toRepresentation(true);

        assertUser(john, "johnkeycloak", "john@email.org", "John", "Doe", "1234");
        assertProfileAttributes(john, null, false, "username", "email", "firstName", "lastName", "postal_code");
        assertProfileAttributes(john, USER_METADATA_GROUP, true,  LDAPConstants.LDAP_ID, LDAPConstants.LDAP_ENTRY_DN);

        // Test Update profile
        john.getRequiredActions().add(UserModel.RequiredAction.UPDATE_PROFILE.toString());
        johnResource.update(john);

        loginPage.open();
        loginPage.login("johnkeycloak", "Password1");
        updateProfilePage.assertCurrent();
        Assert.assertEquals("John", updateProfilePage.getFirstName());
        Assert.assertEquals("Doe", updateProfilePage.getLastName());
        Assert.assertTrue(updateProfilePage.getFieldById("firstName").isEnabled());
        Assert.assertTrue(updateProfilePage.getFieldById("lastName").isEnabled());
        Assert.assertNull(updateProfilePage.getFieldById("postal_code"));
        updateProfilePage.prepareUpdate().submit();
    }

    @Test
    public void testUserProfileWithDefinedAttribute() throws IOException {
        UPConfig origConfig = testRealm().users().userProfile().getConfiguration();
        try {
            UPConfig config = testRealm().users().userProfile().getConfiguration();
            // Set postal code
            UPAttribute postalCode = new UPAttribute();
            postalCode.setName("postal_code");
            postalCode.setDisplayName("Postal Code");

            UPAttributePermissions permissions = new UPAttributePermissions();
            permissions.setView(Set.of(UPConfigUtils.ROLE_USER, UPConfigUtils.ROLE_ADMIN));
            permissions.setEdit(Set.of(UPConfigUtils.ROLE_USER, UPConfigUtils.ROLE_ADMIN));
            postalCode.setPermissions(permissions);
            config.getAttributes().add(postalCode);
            testRealm().users().userProfile().update(config);

            // Defined postal_code in user profile config should have preference
            UserResource johnResource = ApiUtil.findUserByUsernameId(testRealm(), "johnkeycloak");
            UserRepresentation john = johnResource.toRepresentation(true);
            Assert.assertEquals("Postal Code", john.getUserProfileMetadata().getAttributeMetadata("postal_code").getDisplayName());

            // update profile now.
            john.getRequiredActions().add(UserModel.RequiredAction.UPDATE_PROFILE.toString());
            johnResource.update(john);

            loginPage.open();
            loginPage.login("johnkeycloak", "Password1");
            updateProfilePage.assertCurrent();

            Assert.assertEquals("John", updateProfilePage.getFirstName());
            Assert.assertEquals("Doe", updateProfilePage.getLastName());
            Assert.assertEquals("1234", updateProfilePage.getFieldById("postal_code").getAttribute("value"));
            Assert.assertTrue(updateProfilePage.getFieldById("firstName").isEnabled());
            Assert.assertTrue(updateProfilePage.getFieldById("lastName").isEnabled());
            Assert.assertTrue(updateProfilePage.getFieldById("postal_code").isEnabled());
            updateProfilePage.prepareUpdate().submit();
        } finally {
            testRealm().users().userProfile().update(origConfig);
        }
    }

    @Test
    public void testUserProfileWithReadOnlyLdap() {
        // Test user profile of user johnkeycloak in admin console as well as account console. Check attributes are writable.
        setLDAPReadOnly();
        try {
            UserResource johnResource = ApiUtil.findUserByUsernameId(testRealm(), "johnkeycloak");
            UserRepresentation john = johnResource.toRepresentation(true);

            assertProfileAttributes(john, null, true, "username", "email", "firstName", "lastName", "postal_code");
            assertProfileAttributes(john, USER_METADATA_GROUP, true,  LDAPConstants.LDAP_ID, LDAPConstants.LDAP_ENTRY_DN);

            // Test Update profile. Fields are read only
            john.getRequiredActions().add(UserModel.RequiredAction.UPDATE_PROFILE.toString());
            johnResource.update(john);

            loginPage.open();
            loginPage.login("johnkeycloak", "Password1");
            updateProfilePage.assertCurrent();
            Assert.assertEquals("John", updateProfilePage.getFirstName());
            Assert.assertEquals("Doe", updateProfilePage.getLastName());
            Assert.assertFalse(updateProfilePage.getFieldById("firstName").isEnabled());
            Assert.assertFalse(updateProfilePage.getFieldById("lastName").isEnabled());
            Assert.assertNull(updateProfilePage.getFieldById("postal_code"));
            updateProfilePage.prepareUpdate().submit();
        } finally {
            setLDAPWritable();
        }

    }

    @Test
    public void testUserProfileWithReadOnlyLdapLocalUser() {
        // Test local user is writable and has only attributes defined explicitly in user-profile
        setLDAPReadOnly();
        try {
            UserResource maryResource = ApiUtil.findUserByUsernameId(testRealm(), "marykeycloak");
            UserRepresentation mary = maryResource.toRepresentation(true);

            // LDAP is read-only, but local user has all the attributes writable
            assertProfileAttributes(mary, null, false, "username", "email", "firstName", "lastName");
            assertProfileAttributesNotPresent(mary, "postal_code", LDAPConstants.LDAP_ID, LDAPConstants.LDAP_ENTRY_DN);

            // Test Update profile
            mary.getRequiredActions().add(UserModel.RequiredAction.UPDATE_PROFILE.toString());
            maryResource.update(mary);

            loginPage.open();
            loginPage.login("marykeycloak", "Password1");
            updateProfilePage.assertCurrent();
            Assert.assertEquals("Mary", updateProfilePage.getFirstName());
            Assert.assertEquals("Kelly", updateProfilePage.getLastName());
            Assert.assertTrue(updateProfilePage.getFieldById("firstName").isEnabled());
            Assert.assertTrue(updateProfilePage.getFieldById("lastName").isEnabled());
            Assert.assertNull(updateProfilePage.getFieldById("postal_code"));
            updateProfilePage.prepareUpdate().submit();
        } finally {
            setLDAPWritable();
        }
    }

    @Test
    public void testUserProfileWithoutImport() {
        setLDAPImportDisabled();
        try {
            // Test local user is writable and has only attributes defined explicitly in user-profile
            // Test user profile of user johnkeycloak in admin API
            UserResource johnResource = ApiUtil.findUserByUsernameId(testRealm(), "johnkeycloak2");
            UserRepresentation john = johnResource.toRepresentation(true);

            assertUser(john, "johnkeycloak2", "john2@email.org", "John", "Doe", "1234");
            assertProfileAttributes(john, null, false, "username", "email", "firstName", "lastName", "postal_code");
            assertProfileAttributes(john, USER_METADATA_GROUP, true, LDAPConstants.LDAP_ID, LDAPConstants.LDAP_ENTRY_DN);
        } finally {
            setLDAPImportEnabled();
        }
    }

    private void setLDAPReadOnly() {
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            ctx.getLdapModel().getConfig().putSingle(LDAPConstants.EDIT_MODE, UserStorageProvider.EditMode.READ_ONLY.toString());
            appRealm.updateComponent(ctx.getLdapModel());
        });
    }

    private void setLDAPWritable() {
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            ctx.getLdapModel().getConfig().putSingle(LDAPConstants.EDIT_MODE, UserStorageProvider.EditMode.WRITABLE.toString());
            appRealm.updateComponent(ctx.getLdapModel());
        });
    }

    private void setLDAPImportDisabled() {
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            ctx.getLdapModel().getConfig().putSingle(IMPORT_ENABLED, "false");
            appRealm.updateComponent(ctx.getLdapModel());
        });
    }

    private void setLDAPImportEnabled() {
        testingClient.server().run(session -> {
            LDAPTestContext ctx = LDAPTestContext.init(session);
            RealmModel appRealm = ctx.getRealm();

            ctx.getLdapModel().getConfig().putSingle(IMPORT_ENABLED, "true");
            appRealm.updateComponent(ctx.getLdapModel());
        });
    }

    private void assertUser(UserRepresentation user, String expectedUsername, String expectedEmail, String expectedFirstName, String expectedLastname, String expectedPostalCode) {
        Assert.assertNotNull(user);
        Assert.assertEquals(expectedUsername, user.getUsername());
        Assert.assertEquals(expectedFirstName, user.getFirstName());
        Assert.assertEquals(expectedLastname, user.getLastName());
        Assert.assertEquals(expectedEmail, user.getEmail());
        Assert.assertEquals(expectedPostalCode, user.getAttributes().get("postal_code").get(0));

        Assert.assertNotNull(user.getAttributes().get(LDAPConstants.LDAP_ID));
        Assert.assertNotNull(user.getAttributes().get(LDAPConstants.LDAP_ENTRY_DN));
    }


    private void assertProfileAttributes(UserRepresentation user, String expectedGroup, boolean expectReadOnly, String... attributes) {
        for (String attrName : attributes) {
            UserProfileAttributeMetadata attrMetadata = user.getUserProfileMetadata().getAttributeMetadata(attrName);
            Assert.assertNotNull("Attribute " + attrName + " was not present for user " + user.getUsername(), attrMetadata);
            Assert.assertEquals("Attribute " + attrName + " for user " + user.getUsername() + ". Expected read-only: " + expectReadOnly + " but was not", expectReadOnly, attrMetadata.isReadOnly());
            Assert.assertEquals("Attribute " + attrName + " for user " + user.getUsername() + ". Expected group: " + expectedGroup + " but was " + attrMetadata.getGroup(), expectedGroup, attrMetadata.getGroup());
        }
    }

    private void assertProfileAttributesNotPresent(UserRepresentation user, String... attributes) {
        for (String attrName : attributes) {
            UserProfileAttributeMetadata attrMetadata = user.getUserProfileMetadata().getAttributeMetadata(attrName);
            Assert.assertNull("Attribute " + attrName + " was present for user " + user.getUsername(), attrMetadata);
        }
    }

}
