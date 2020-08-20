/**
 * Copyright (C) 2020 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
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
package com.helger.phase4.peppol.server.servlet;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;
import javax.servlet.annotation.WebListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.helger.commons.debug.GlobalDebug;
import com.helger.commons.exception.InitializationException;
import com.helger.commons.io.file.SimpleFileIO;
import com.helger.commons.state.ETriState;
import com.helger.commons.string.StringHelper;
import com.helger.commons.url.URLHelper;
import com.helger.httpclient.HttpDebugger;
import com.helger.json.serialize.JsonWriterSettings;
import com.helger.peppol.utils.EPeppolCertificateCheckResult;
import com.helger.peppol.utils.PeppolCertificateChecker;
import com.helger.phase4.crypto.AS4CryptoFactoryPropertiesFile;
import com.helger.phase4.dump.AS4DumpManager;
import com.helger.phase4.messaging.AS4MessagingHelper;
import com.helger.phase4.messaging.IAS4IncomingMessageMetadata;
import com.helger.phase4.mgr.MetaAS4Manager;
import com.helger.phase4.peppol.server.storage.StorageHelper;
import com.helger.phase4.peppol.servlet.Phase4PeppolServletConfiguration;
import com.helger.phase4.profile.peppol.AS4PeppolProfileRegistarSPI;
import com.helger.phase4.servlet.AS4ServerInitializer;
import com.helger.phase4.servlet.dump.AS4IncomingDumperFileBased;
import com.helger.phase4.servlet.dump.AS4OutgoingDumperFileBased;
import com.helger.phase4.servlet.mgr.AS4ProfileSelector;
import com.helger.phase4.servlet.mgr.AS4ServerConfiguration;
import com.helger.photon.core.servlet.WebAppListener;
import com.helger.photon.security.CSecurity;
import com.helger.photon.security.mgr.PhotonSecurityManager;
import com.helger.photon.security.user.IUserManager;
import com.helger.smpclient.peppol.SMPClientReadOnly;
import com.helger.xservlet.requesttrack.RequestTracker;

@WebListener
public final class Phase4PeppolWebAppListener extends WebAppListener
{
  private static final Logger LOGGER = LoggerFactory.getLogger (Phase4PeppolWebAppListener.class);

  @Override
  @Nullable
  protected String getInitParameterDebug (@Nonnull final ServletContext aSC)
  {
    return Boolean.toString (AS4ServerConfiguration.isGlobalDebug ());
  }

  @Override
  @Nullable
  protected String getInitParameterProduction (@Nonnull final ServletContext aSC)
  {
    return Boolean.toString (AS4ServerConfiguration.isGlobalProduction ());
  }

  @Override
  @Nullable
  protected String getInitParameterNoStartupInfo (@Nonnull final ServletContext aSC)
  {
    return Boolean.toString (AS4ServerConfiguration.isNoStartupInfo ());
  }

  @Override
  protected String getDataPath (@Nonnull final ServletContext aSC)
  {
    return AS4ServerConfiguration.getDataPath ();
  }

  @Override
  protected boolean shouldCheckFileAccess (@Nonnull final ServletContext aSC)
  {
    return false;
  }

  @Override
  protected void afterContextInitialized (@Nonnull final ServletContext aSC)
  {
    super.afterContextInitialized (aSC);

    // Show registered servlets
    for (final Map.Entry <String, ? extends ServletRegistration> aEntry : aSC.getServletRegistrations ().entrySet ())
      LOGGER.info ("Servlet '" + aEntry.getKey () + "' is mapped to " + aEntry.getValue ().getMappings ());
  }

  @Override
  protected void initGlobalSettings ()
  {
    // Logging: JUL to SLF4J
    SLF4JBridgeHandler.removeHandlersForRootLogger ();
    SLF4JBridgeHandler.install ();

    if (GlobalDebug.isDebugMode ())
      RequestTracker.getInstance ().getRequestTrackingMgr ().setLongRunningCheckEnabled (false);

    HttpDebugger.setEnabled (false);

    // Enforce Peppol profile usage
    AS4ProfileSelector.setCustomAS4ProfileID (AS4PeppolProfileRegistarSPI.AS4_PROFILE_ID);
  }

  @Override
  protected void initSecurity ()
  {
    // Ensure user exists
    final IUserManager aUserMgr = PhotonSecurityManager.getUserMgr ();
    if (!aUserMgr.containsWithID (CSecurity.USER_ADMINISTRATOR_ID))
    {
      aUserMgr.createPredefinedUser (CSecurity.USER_ADMINISTRATOR_ID,
                                     CSecurity.USER_ADMINISTRATOR_LOGIN,
                                     CSecurity.USER_ADMINISTRATOR_EMAIL,
                                     CSecurity.USER_ADMINISTRATOR_PASSWORD,
                                     "Admin",
                                     "istrator",
                                     null,
                                     Locale.US,
                                     null,
                                     false);
    }
  }

  private void _initAS4 ()
  {
    AS4ServerInitializer.initAS4Server ();

    // Store the incoming file as is
    AS4DumpManager.setIncomingDumper (new AS4IncomingDumperFileBased ( (aMessageMetadata,
                                                                        aHttpHeaderMap) -> StorageHelper.getStorageFile (aMessageMetadata,
                                                                                                                         ".as4in"))
    {
      public void onEndRequest (@Nonnull final IAS4IncomingMessageMetadata aMessageMetadata)
      {
        // Save the metadata also to a file
        final File aFile = StorageHelper.getStorageFile (aMessageMetadata, ".metadata");
        if (SimpleFileIO.writeFile (aFile,
                                    AS4MessagingHelper.getIncomingMetadataAsJson (aMessageMetadata)
                                                      .getAsJsonString (new JsonWriterSettings ().setIndentEnabled (true)),
                                    StandardCharsets.UTF_8)
                        .isFailure ())
          LOGGER.error ("Failed to write metadata to '" + aFile.getAbsolutePath () + "'");
        else
          LOGGER.info ("Wrote metadata to '" + aFile.getAbsolutePath () + "'");
      }
    });

    // Store the outgoings file as well
    AS4DumpManager.setOutgoingDumper (new AS4OutgoingDumperFileBased ( (sMessageID, nTry) -> StorageHelper.getStorageFile (sMessageID,
                                                                                                                           nTry,
                                                                                                                           ".as4out")));
  }

  private void _initPeppolAS4 ()
  {
    // Check if crypto properties are okay
    final KeyStore aKS = AS4CryptoFactoryPropertiesFile.getDefaultInstance ().getKeyStore ();
    if (aKS == null)
      throw new InitializationException ("Failed to load configured Keystore");

    final PrivateKeyEntry aPKE = AS4CryptoFactoryPropertiesFile.getDefaultInstance ().getPrivateKeyEntry ();
    if (aPKE == null)
      throw new InitializationException ("Failed to load configured private key");

    // No OCSP check for performance
    final X509Certificate aAPCert = (X509Certificate) aPKE.getCertificate ();
    final EPeppolCertificateCheckResult eCheckResult = PeppolCertificateChecker.checkPeppolAPCertificate (aAPCert,
                                                                                                          MetaAS4Manager.getTimestampMgr ()
                                                                                                                        .getCurrentDateTime (),
                                                                                                          ETriState.FALSE,
                                                                                                          ETriState.UNDEFINED);
    if (eCheckResult.isInvalid ())
      throw new InitializationException ("The provided certificate is not a Peppol certificate. Check result: " + eCheckResult);
    LOGGER.info ("Sucessfully checked that the provided Peppol AP certificate is valid.");

    final String sSMPURL = AS4ServerConfiguration.getSettings ().getAsString ("smp.url");
    final String sAPURL = AS4ServerConfiguration.getServerAddress ();
    if (StringHelper.hasText (sSMPURL) && StringHelper.hasText (sAPURL))
    {
      Phase4PeppolServletConfiguration.setReceiverCheckEnabled (true);
      Phase4PeppolServletConfiguration.setSMPClient (new SMPClientReadOnly (URLHelper.getAsURI (sSMPURL)));
      Phase4PeppolServletConfiguration.setAS4EndpointURL (sAPURL);
      Phase4PeppolServletConfiguration.setAPCertificate (aAPCert);
      LOGGER.info ("phase4 Peppol receiver checks are enabled");
    }
    else
    {
      Phase4PeppolServletConfiguration.setReceiverCheckEnabled (false);
      LOGGER.warn ("phase4 Peppol receiver checks are disabled");
    }
  }

  @Override
  protected void initManagers ()
  {
    _initAS4 ();
    _initPeppolAS4 ();
  }

  @Override
  protected void beforeContextDestroyed (@Nonnull final ServletContext aSC)
  {
    AS4ServerInitializer.shutdownAS4Server ();
  }
}
