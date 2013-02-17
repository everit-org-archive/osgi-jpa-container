package org.everit.osgi.jpa.container;

/*
 * Copyright (c) 2011, Everit Kft.
 *
 * All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManagerFactory;
import javax.persistence.spi.PersistenceProvider;
import javax.sql.DataSource;
import javax.sql.XADataSource;

import org.apache.aries.jpa.container.PersistenceUnitConstants;
import org.apache.aries.jpa.container.parsing.ParsedPersistenceUnit;
import org.apache.aries.jpa.container.tx.impl.XADatasourceEnlistingWrapper;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.jdbc.DataSourceFactory;
import org.osgi.service.jpa.EntityManagerFactoryBuilder;

/**
 * Creates persistence units and handles their lifecycle.
 */
public class EntityManagerFactoryManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityManagerFactoryManager.class);

    /**
     * ServiceReferences of Persistence Units using by bundles.
     */
    private Map<ParsedPersistenceUnit, List<ServiceReference>> holdedReferencesByPPUs =
            new ConcurrentHashMap<ParsedPersistenceUnit, List<ServiceReference>>();

    private Map<ParsedPersistenceUnit, EntityManagerFactory> emfsByPPU =
            new ConcurrentHashMap<ParsedPersistenceUnit, EntityManagerFactory>();

    private Map<ParsedPersistenceUnit, ServiceRegistration> emfRegistrationsByPPU =
            new ConcurrentHashMap<ParsedPersistenceUnit, ServiceRegistration>();

    public void startPersistenceUnit(final ParsedPersistenceUnit ppu, ServiceReference persistenceProviderSR,
            final Map<String, ServiceReference> serviceReferencesByIds) {
        Bundle bundle = ppu.getDefiningBundle();
        BundleContext bcx = bundle.getBundleContext();
        List<ServiceReference> holdedReferences = holdedReferencesByPPUs.get(ppu);

        if (holdedReferences == null) {
            holdedReferences = new ArrayList<ServiceReference>();
            holdedReferencesByPPUs.put(ppu, holdedReferences);
        }

        PersistenceProvider persistenceProviderService = (PersistenceProvider) getServiceObject(bcx,
                persistenceProviderSR, holdedReferences);

        DataSource nonJtaDataSource = (DataSource) getServiceObject(bcx,
                serviceReferencesByIds.get(PersistenceBundleManager.DATASOURCE_REFERENCE_ID), holdedReferences);

        DataSource jtaDataSource = (DataSource) getServiceObject(bcx,
                serviceReferencesByIds.get(PersistenceBundleManager.XA_DATASOURCE_REFERENCE_ID), holdedReferences);

        if (nonJtaDataSource == null && jtaDataSource == null) {
            DataSourceFactory dsf = (DataSourceFactory) getServiceObject(bcx,
                    serviceReferencesByIds.get(PersistenceBundleManager.DATASOURCE_FACTORY_REFERENCE_ID),
                    holdedReferences);

            if (dsf != null) {
                Properties dsfProps = new Properties();
                Properties props = (Properties) ppu.getPersistenceXmlMetadata().get(ParsedPersistenceUnit.PROPERTIES);

                String url = props.getProperty("javax.persistence.jdbc.url");
                String user = props.getProperty("javax.persistence.jdbc.user");
                String password = props.getProperty("javax.persistence.jdbc.password");
                dsfProps.setProperty(DataSourceFactory.JDBC_URL, url);
                dsfProps.setProperty(DataSourceFactory.JDBC_USER, user);
                dsfProps.setProperty(DataSourceFactory.JDBC_PASSWORD, password);
                try {
                    nonJtaDataSource = dsf.createDataSource(dsfProps);
                    if (PersistenceBundleManager.isJTANecessary(ppu)) {
                        XADataSource xaDataSource = dsf.createXADataSource(dsfProps);
                        jtaDataSource = new XADatasourceEnlistingWrapper(xaDataSource);
                    }
                } catch (SQLException e) {
                    LOGGER.error("Error during the creation of datasource for Persistence Unit", e);
                    stopPersistenceUnit(ppu);
                    return;
                }
            } else {
                // We may have simple DataSources to get from JNDI.
                Map<String, Object> persistenceXmlMetadata = ppu.getPersistenceXmlMetadata();
                String jtaDSJNDIName = (String) persistenceXmlMetadata.get(ParsedPersistenceUnit.JTA_DATASOURCE);

                if (jtaDSJNDIName != null) {
                    try {
                        jtaDataSource = InitialContext.doLookup(jtaDSJNDIName);
                    } catch (NamingException e) {
                        LOGGER.error("Cannot get jta datasource from JNDI: " + jtaDSJNDIName);
                    }
                }

                String nonJtaDSJNDIName = (String) persistenceXmlMetadata.get(ParsedPersistenceUnit.NON_JTA_DATASOURCE);
                if (nonJtaDSJNDIName != null) {
                    try {
                        nonJtaDataSource = InitialContext.doLookup(nonJtaDSJNDIName);
                    } catch (NamingException e) {
                        LOGGER.error("Cannot get jta datasource from JNDI: " + jtaDSJNDIName);
                    }
                }
            }
        }
        if (jtaDataSource != null || nonJtaDataSource != null) {
            PersistenceUnitInfoImpl info = new PersistenceUnitInfoImpl(ppu, persistenceProviderSR, jtaDataSource,
                    nonJtaDataSource);
            EntityManagerFactory emf = persistenceProviderService.createContainerEntityManagerFactory(
                    info, null);
            emfsByPPU.put(ppu, emf);

            String unitName = (String) ppu.getPersistenceXmlMetadata().get(ParsedPersistenceUnit.UNIT_NAME);
            Dictionary<String, Object> emfServiceProps = new Hashtable<String, Object>() {
            };
            emfServiceProps.put(EntityManagerFactoryBuilder.JPA_UNIT_NAME, unitName);
            String providerName = (String) persistenceProviderSR.getProperty("javax.persistence.provider");
            if (providerName != null) {
                emfServiceProps.put(EntityManagerFactoryBuilder.JPA_UNIT_PROVIDER, providerName);
            }

            emfServiceProps.put(EntityManagerFactoryBuilder.JPA_UNIT_VERSION, bundle.getVersion());
            emfServiceProps.put(PersistenceUnitConstants.CONTAINER_MANAGED_PERSISTENCE_UNIT, Boolean.TRUE);
            ServiceRegistration emfServiceRegistration = bcx.registerService(EntityManagerFactory.class.getName(), emf,
                    emfServiceProps);
            emfRegistrationsByPPU.put(ppu, emfServiceRegistration);
        } else {
            LOGGER.error("Neither jta datasource nor non-jta datasource is available for pu creation: "
                    + ppu.toString());
        }
    }

    private Object getServiceObject(BundleContext bcx, ServiceReference sr, List<ServiceReference> holdedReferences) {
        if (sr == null) {
            return null;
        }
        Object service = bcx.getService(sr);
        if (service != null) {
            holdedReferences.add(sr);
        }
        return service;
    }

    public void stopPersistenceUnit(ParsedPersistenceUnit ppu) {
        ServiceRegistration serviceRegistration = emfRegistrationsByPPU.get(ppu);
        if (serviceRegistration != null) {
            serviceRegistration.unregister();
        }

        EntityManagerFactory emf = emfsByPPU.get(ppu);
        if (emf != null) {
            emf.close();
        }

        List<ServiceReference> holdedReferences = holdedReferencesByPPUs.get(ppu);
        if (holdedReferences != null) {
            BundleContext bcx = ppu.getDefiningBundle().getBundleContext();
            for (ServiceReference serviceReference : holdedReferences) {
                bcx.ungetService(serviceReference);
            }
        }
    }
}
