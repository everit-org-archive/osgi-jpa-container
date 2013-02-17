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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;
import javax.sql.XADataSource;
import javax.transaction.TransactionManager;

import org.apache.aries.jpa.container.impl.PersistenceBundleHelper;
import org.apache.aries.jpa.container.parsing.ParsedPersistenceUnit;
import org.apache.aries.jpa.container.parsing.PersistenceDescriptor;
import org.apache.aries.jpa.container.parsing.PersistenceDescriptorParser;
import org.apache.aries.jpa.container.parsing.PersistenceDescriptorParserException;
import org.apache.aries.jpa.container.parsing.impl.PersistenceDescriptorParserImpl;
import org.apache.aries.jpa.container.tx.impl.OSGiTransactionManager;
import org.everit.osgi.util.core.requisite.AbstractRequisiteTracker;
import org.everit.osgi.util.core.requisite.MultiRequisiteListener;
import org.everit.osgi.util.core.requisite.MultiRequisiteManager;
import org.everit.osgi.util.core.requisite.RequisiteListener;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.service.jdbc.DataSourceFactory;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.BundleTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class locates, parses and manages persistence units defined in OSGi bundles.
 */
public class PersistenceBundleManager implements BundleTrackerCustomizer, BundleActivator {

    /**
     * Id for the datasource factory service tracker.
     */
    public static final String DATASOURCE_FACTORY_REFERENCE_ID = DataSourceFactory.class.getName();

    /**
     * Id of the datasource service tracker.
     */
    public static final String DATASOURCE_REFERENCE_ID = DataSource.class.getName();

    /**
     * XADataSource service tracker id.
     */
    public static final String XA_DATASOURCE_REFERENCE_ID = XADataSource.class.getName();

    /**
     * Id of the Java transactionmanager.
     */
    public static final String TRANSACTION_MANAGER_REFERENCE_ID = TransactionManager.class.getName();

    /**
     * {@link RequisiteListener} that catches the presence of Persistence Providers. When a persistence provider for a
     * persistence bundle is ready the tarcking of other requisites like DataSourceFactory or DataSource will be
     * started.
     */
    private RequisiteListener<BundleWithParsedPersistenceUnits> persistenceProviderRequisiteListener =
            new RequisiteListener<BundleWithParsedPersistenceUnits>() {

                @Override
                public void requisiteAvailable(final BundleWithParsedPersistenceUnits dependentObject,
                        final ServiceReference reference) {
                    Bundle bundle = dependentObject.getBundle();
                    LOGGER.info("PersistenceProvider available for bundle " + bundle.getSymbolicName() + ":"
                            + bundle.getVersion().toString());
                    persistenceProviderServiceReferences.put(bundle, reference);
                    addParsedPersistenceUnitsToMultiRequisiteTracker(dependentObject.getParsedPersistenceUnits());
                }

                @Override
                public void requisiteRemoved(final BundleWithParsedPersistenceUnits dependentObject,
                        final ServiceReference reference) {
                    Bundle bundle = dependentObject.getBundle();
                    LOGGER.info("PersistenceProvider not available anymore for bundle " + bundle.getSymbolicName() + ":"
                            + bundle.getVersion().toString() + ". Removing persistence units.");
                    persistenceProviderServiceReferences.remove(bundle);
                    removeParsedPersistenceUnitsFromMultiRequsiteTracker(dependentObject.getParsedPersistenceUnits());
                }
            };

    /** Logger. */
    private static final Logger LOGGER = LoggerFactory.getLogger("org.apache.aries.jpa.container");

    static boolean isJTANecessary(final ParsedPersistenceUnit ppu) {
        if (ppu.getPersistenceXmlMetadata().get(ParsedPersistenceUnit.JTA_DATASOURCE) != null) {
            return true;
        }
        String ttype = (String) ppu.getPersistenceXmlMetadata().get(ParsedPersistenceUnit.TRANSACTION_TYPE);
        if ((ttype == null) || "JTA".equals(ttype)) {
            return true;
        } else {
            return false;
        }
    }

    /** The bundle context for this bundle. */
    private BundleContext ctx = null;
    /**
     * When every requisite is ready let the emf manager create the emfs and destroy them if necessary.
     */
    private EntityManagerFactoryManager emfManager = new EntityManagerFactoryManager();

    /**
     * The tracker of persistence providers.
     */
    private PersistenceProviderRequisiteTracker persistenceProviderRequisiteTracker;

    /** Parser for persistence descriptors. */
    private PersistenceDescriptorParser persistenceDescriptorParser;

    /**
     * The tracker of persistence bundles.
     */
    private BundleTracker persistenceBundleTracker;

    private DataSourceRequisiteTracker jtaDataSourceRequisiteTracker = null;

    private DataSourceRequisiteTracker nonJtaDataSourceRequisiteTracker = null;

    /**
     * The service references of persistence providers based on the bundles that need it.
     */
    private Map<Bundle, ServiceReference> persistenceProviderServiceReferences =
            new ConcurrentHashMap<Bundle, ServiceReference>();

    /**
     * Tracking of JTA Transaction manager as a requisite.
     */
    private JTARequisiteTracker jtaRequisiteTracker = null;

    /**
     * Tracking multiple requisites of a parsed persistence unit. Multiple requisites can be for example
     * DataSourceFactory and TransactionManager for a persistence unit that needs these two.
     */
    private MultiRequisiteManager<ParsedPersistenceUnit> parsedPUMultiRequisiteTracker =
            new MultiRequisiteManager<ParsedPersistenceUnit>(
                    new MultiRequisiteListener<ParsedPersistenceUnit>() {
                        @Override
                        public void startDependentObject(final ParsedPersistenceUnit dependentObject,
                                final Map<String, ServiceReference> references) {
                            startPersistenceUnit(dependentObject, references);
                        }

                        @Override
                        public void stopDependentObject(final ParsedPersistenceUnit dependentObject) {
                            stopPersistenceUnit(dependentObject);
                        }
                    });

    /**
     * Parsed persistence units by the bundles that contain them.
     */
    private Map<Bundle, BundleWithParsedPersistenceUnits> parsedPUByBundle =
            new ConcurrentHashMap<Bundle, BundleWithParsedPersistenceUnits>();

    /**
     * Requisite tracker for DataSourceFactory services.
     */
    private DataSourceFactoryRequisitTracker dataSourceFactoryRequisiteTracker = null;

    @Override
    public Object addingBundle(final Bundle bundle, final BundleEvent event) {
        final Collection<ParsedPersistenceUnit> pUnits = parseBundle(bundle);
        if (!pUnits.isEmpty()) {
            final BundleWithParsedPersistenceUnits bundleWithParsedPersistenceUnits =
                    new BundleWithParsedPersistenceUnits(bundle, pUnits);

            parsedPUByBundle.put(bundle, bundleWithParsedPersistenceUnits);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Located Persistence units: " + pUnits);
            }

            if (bundle.getState() == Bundle.ACTIVE) {
                persistenceProviderRequisiteTracker.addDependentObject(bundleWithParsedPersistenceUnits,
                        persistenceProviderRequisiteListener);
            }
            return bundle;
        }
        return null;
    }

    private void addParsedPersistenceUnitsToMultiRequisiteTracker(
            final Collection<ParsedPersistenceUnit> parsedPersistenceUnits) {

        for (ParsedPersistenceUnit pUnit : parsedPersistenceUnits) {
            Map<String, Object> persistenceXmlMetadata = pUnit.getPersistenceXmlMetadata();
            Map<String, AbstractRequisiteTracker<ParsedPersistenceUnit>> parsedPUTrackers =
                    new HashMap<String, AbstractRequisiteTracker<ParsedPersistenceUnit>>();

            Properties props = (Properties) persistenceXmlMetadata.get(ParsedPersistenceUnit.PROPERTIES);
            if (props.get("javax.persistence.jdbc.driver") != null) {
                parsedPUTrackers.put(DATASOURCE_FACTORY_REFERENCE_ID, dataSourceFactoryRequisiteTracker);
            }

            if (PersistenceBundleManager.isJTANecessary(pUnit)) {
                parsedPUTrackers.put(TRANSACTION_MANAGER_REFERENCE_ID, jtaRequisiteTracker);
            }

            String jtaDataSourceExpression = (String) persistenceXmlMetadata.get(ParsedPersistenceUnit.JTA_DATASOURCE);
            if ((jtaDataSourceExpression != null) && jtaDataSourceExpression.startsWith("osgi:service")) {
                parsedPUTrackers.put(XADataSource.class.getName(), jtaDataSourceRequisiteTracker);
            }

            String nonJtaDataSourceExpression = (String) persistenceXmlMetadata
                    .get(ParsedPersistenceUnit.NON_JTA_DATASOURCE);

            if ((nonJtaDataSourceExpression != null) && nonJtaDataSourceExpression.startsWith("osgi:service")) {
                parsedPUTrackers.put(DataSource.class.getName(), nonJtaDataSourceRequisiteTracker);
            }
            parsedPUMultiRequisiteTracker.registerDependentObject(pUnit,
                    parsedPUTrackers);
        }
    }

    public BundleContext getCtx() {
        return ctx;
    }

    @Override
    public void modifiedBundle(final Bundle bundle, final BundleEvent event, final Object object) {

        if (event.getType() == BundleEvent.STOPPING) {
            BundleWithParsedPersistenceUnits bundleWithParsedPersistenceUnits = parsedPUByBundle.get(bundle);
            persistenceProviderRequisiteTracker.removeDependentObject(bundleWithParsedPersistenceUnits);
            Collection<ParsedPersistenceUnit> ppus = bundleWithParsedPersistenceUnits.getParsedPersistenceUnits();
            removeParsedPersistenceUnitsFromMultiRequsiteTracker(ppus);

        }
        if (event.getType() == BundleEvent.UPDATED) {
            Collection<ParsedPersistenceUnit> newPUnits = parseBundle(bundle);
            BundleWithParsedPersistenceUnits bundleWithParsedPersistenceUnits = new BundleWithParsedPersistenceUnits(
                    bundle,
                    newPUnits);
            parsedPUByBundle.put(bundle, bundleWithParsedPersistenceUnits);
        }

        if (event.getType() == BundleEvent.STARTED) {
            persistenceProviderRequisiteTracker.addDependentObject(parsedPUByBundle.get(bundle),
                    persistenceProviderRequisiteListener);
        }
    }

    private Collection<ParsedPersistenceUnit> parseBundle(final Bundle b) {

        Collection<ParsedPersistenceUnit> pUnits = new ArrayList<ParsedPersistenceUnit>();

        Collection<PersistenceDescriptor> persistenceXmls = PersistenceBundleHelper.findPersistenceXmlFiles(b);

        // If we have no persistence units then our job is done
        if (!!!persistenceXmls.isEmpty()) {
            // Parse each descriptor
            for (PersistenceDescriptor descriptor : persistenceXmls) {
                try {
                    pUnits.addAll(persistenceDescriptorParser.parse(b, descriptor));
                } catch (PersistenceDescriptorParserException e) {
                    LOGGER.error("Cannot parse persistence descriptor", e);
                }
            }
        }
        return pUnits;
    }

    @Override
    public void removedBundle(final Bundle bundle, final BundleEvent event, final Object object) {
        BundleWithParsedPersistenceUnits bundleWithParsedPersistenceUnits = parsedPUByBundle.remove(bundle);
        persistenceProviderRequisiteTracker.removeDependentObject(bundleWithParsedPersistenceUnits);
        Collection<ParsedPersistenceUnit> ppus = bundleWithParsedPersistenceUnits.getParsedPersistenceUnits();
        removeParsedPersistenceUnitsFromMultiRequsiteTracker(ppus);

    }

    private void removeParsedPersistenceUnitsFromMultiRequsiteTracker(final Collection<ParsedPersistenceUnit> pUnits) {
        for (ParsedPersistenceUnit parsedPersistenceUnit : pUnits) {
            parsedPUMultiRequisiteTracker.removeDependentObject(parsedPersistenceUnit);
        }

    }

    @Override
    public void start(final BundleContext context) throws Exception {

        ctx = context;

        persistenceDescriptorParser = new PersistenceDescriptorParserImpl();
        persistenceProviderRequisiteTracker = new PersistenceProviderRequisiteTracker(context, null);
        dataSourceFactoryRequisiteTracker = new DataSourceFactoryRequisitTracker(context, this);
        jtaRequisiteTracker = new JTARequisiteTracker(context, this);
        jtaDataSourceRequisiteTracker = new DataSourceRequisiteTracker(context, true);
        nonJtaDataSourceRequisiteTracker = new DataSourceRequisiteTracker(context, false);

        persistenceBundleTracker = new BundleTracker(ctx, Bundle.INSTALLED | Bundle.RESOLVED | Bundle.STARTING
                | Bundle.ACTIVE | Bundle.STOPPING, this);

        persistenceProviderRequisiteTracker.open();
        dataSourceFactoryRequisiteTracker.open();
        jtaRequisiteTracker.open();
        jtaDataSourceRequisiteTracker.open();
        nonJtaDataSourceRequisiteTracker.open();
        persistenceBundleTracker.open();

    }

    private void startPersistenceUnit(final ParsedPersistenceUnit ppu,
            final Map<String, ServiceReference> serviceReferencesByIds) {

        ServiceReference persistenceProviderSR = persistenceProviderServiceReferences.get(ppu.getDefiningBundle());

        emfManager.startPersistenceUnit(ppu, persistenceProviderSR, serviceReferencesByIds);
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        if (persistenceBundleTracker != null) {
            persistenceBundleTracker.close();
        }

        if (persistenceProviderRequisiteTracker != null) {
            persistenceProviderRequisiteTracker.close();
        }

        if (dataSourceFactoryRequisiteTracker != null) {
            dataSourceFactoryRequisiteTracker.close();
        }

        if (jtaRequisiteTracker != null) {
            jtaRequisiteTracker.close();
        }

        if (jtaDataSourceRequisiteTracker != null) {
            jtaDataSourceRequisiteTracker.close();
        }

        if (nonJtaDataSourceRequisiteTracker != null) {
            nonJtaDataSourceRequisiteTracker.close();
        }

        OSGiTransactionManager otm = OSGiTransactionManager.get();

        if (otm != null) {
            otm.destroy();
        }
    }

    private void stopPersistenceUnit(final ParsedPersistenceUnit ppu) {
        emfManager.stopPersistenceUnit(ppu);
    }
}
