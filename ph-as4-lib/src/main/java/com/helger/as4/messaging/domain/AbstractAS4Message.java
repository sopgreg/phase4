/**
 * Copyright (C) 2015-2019 Philip Helger (www.helger.com)
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
package com.helger.as4.messaging.domain;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.xml.namespace.QName;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import com.helger.as4.CAS4;
import com.helger.as4.marshaller.Ebms3WriterBuilder;
import com.helger.as4.soap.ESOAPVersion;
import com.helger.as4lib.ebms3header.Ebms3Messaging;
import com.helger.as4lib.soap11.Soap11Body;
import com.helger.as4lib.soap11.Soap11Envelope;
import com.helger.as4lib.soap11.Soap11Header;
import com.helger.as4lib.soap12.Soap12Body;
import com.helger.as4lib.soap12.Soap12Envelope;
import com.helger.as4lib.soap12.Soap12Header;
import com.helger.commons.ValueEnforcer;
import com.helger.commons.annotation.Nonempty;
import com.helger.commons.string.ToStringGenerator;
import com.helger.commons.traits.IGenericImplTrait;

/**
 * Abstract AS4 message implementation
 *
 * @author Philip Helger
 * @param <IMPLTYPE>
 *        Real implementation type.
 */
public abstract class AbstractAS4Message <IMPLTYPE extends AbstractAS4Message <IMPLTYPE>> implements
                                         IAS4Message,
                                         IGenericImplTrait <IMPLTYPE>
{
  private final ESOAPVersion m_eSOAPVersion;
  private final EAS4MessageType m_eMsgType;
  private final String m_sMessagingID;
  protected final Ebms3Messaging m_aMessaging = new Ebms3Messaging ();

  @Nonnull
  @Nonempty
  public static String createRandomMessagingID ()
  {
    // Assign a random ID for signing
    // Data type is "xs:ID", derived from "xs:NCName"
    // --> cannot start with a number
    return CAS4.LIB_NAME + "-" + UUID.randomUUID ().toString ();
  }

  public AbstractAS4Message (@Nonnull final ESOAPVersion eSOAPVersion, @Nonnull final EAS4MessageType eMsgType)
  {
    m_eSOAPVersion = ValueEnforcer.notNull (eSOAPVersion, "SOAPVersion");
    m_eMsgType = ValueEnforcer.notNull (eMsgType, "MessageType");
    m_sMessagingID = createRandomMessagingID ();

    // Must be a "wsu:Id" for WSSec to be found
    m_aMessaging.getOtherAttributes ().put (new QName (CAS4.WSU_NS, "Id"), m_sMessagingID);
  }

  @Nonnull
  public final ESOAPVersion getSOAPVersion ()
  {
    return m_eSOAPVersion;
  }

  @Nonnull
  public final EAS4MessageType getMessageType ()
  {
    return m_eMsgType;
  }

  @Nonnull
  @Nonempty
  public final String getMessagingID ()
  {
    return m_sMessagingID;
  }

  @Nonnull
  public final IMPLTYPE setMustUnderstand (final boolean bMustUnderstand)
  {
    switch (m_eSOAPVersion)
    {
      case SOAP_11:
        m_aMessaging.setS11MustUnderstand (Boolean.valueOf (bMustUnderstand));
        break;
      case SOAP_12:
        m_aMessaging.setS12MustUnderstand (Boolean.valueOf (bMustUnderstand));
        break;
      default:
        throw new IllegalStateException ("Unsupported SOAP version " + m_eSOAPVersion);
    }
    return thisAsT ();
  }

  @Nonnull
  public final Document getAsSOAPDocument (@Nullable final Node aPayload)
  {
    // Convert to DOM Node
    final Document aEbms3Document = Ebms3WriterBuilder.ebms3Messaging ().getAsDocument (m_aMessaging);
    if (aEbms3Document == null)
      throw new IllegalStateException ("Failed to write EBMS3 Messaging to XML");

    final Node aRealPayload = aPayload instanceof Document ? ((Document) aPayload).getDocumentElement () : aPayload;

    switch (m_eSOAPVersion)
    {
      case SOAP_11:
      {
        // Creating SOAP 11 Envelope
        final Soap11Envelope aSoapEnv = new Soap11Envelope ();
        aSoapEnv.setHeader (new Soap11Header ());
        aSoapEnv.setBody (new Soap11Body ());
        aSoapEnv.getHeader ().addAny (aEbms3Document.getDocumentElement ());
        if (aRealPayload != null)
          aSoapEnv.getBody ().addAny (aRealPayload);
        return Ebms3WriterBuilder.soap11 ().getAsDocument (aSoapEnv);
      }
      case SOAP_12:
      {
        // Creating SOAP 12 Envelope
        final Soap12Envelope aSoapEnv = new Soap12Envelope ();
        aSoapEnv.setHeader (new Soap12Header ());
        aSoapEnv.setBody (new Soap12Body ());
        aSoapEnv.getHeader ().addAny (aEbms3Document.getDocumentElement ());
        if (aRealPayload != null)
          aSoapEnv.getBody ().addAny (aRealPayload);
        return Ebms3WriterBuilder.soap12 ().getAsDocument (aSoapEnv);
      }
      default:
        throw new IllegalStateException ("Unsupported SOAP version " + m_eSOAPVersion);
    }
  }

  @Override
  public String toString ()
  {
    return new ToStringGenerator (this).append ("SOAPVersion", m_eSOAPVersion)
                                       .append ("MsgType", m_eMsgType)
                                       .append ("MessagingID", m_sMessagingID)
                                       .getToString ();
  }
}
