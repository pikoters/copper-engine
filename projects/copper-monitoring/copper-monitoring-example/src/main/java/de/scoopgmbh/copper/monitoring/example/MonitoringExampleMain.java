/*
 * Copyright 2002-2013 SCOOP Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.scoopgmbh.copper.monitoring.example;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.sql.DataSource;

import org.apache.derby.jdbc.EmbeddedConnectionPoolDataSource40;

import de.scoopgmbh.copper.CopperException;
import de.scoopgmbh.copper.audit.BatchingAuditTrail;
import de.scoopgmbh.copper.audit.CompressedBase64PostProcessor;
import de.scoopgmbh.copper.batcher.RetryingTxnBatchRunner;
import de.scoopgmbh.copper.batcher.impl.BatcherImpl;
import de.scoopgmbh.copper.common.DefaultProcessorPoolManager;
import de.scoopgmbh.copper.common.JdkRandomUUIDFactory;
import de.scoopgmbh.copper.db.utility.RetryingTransaction;
import de.scoopgmbh.copper.management.ProcessingEngineMXBean;
import de.scoopgmbh.copper.monitoring.LoggingStatisticCollector;
import de.scoopgmbh.copper.monitoring.example.adapter.BillAdapterImpl;
import de.scoopgmbh.copper.monitoring.server.DefaultCopperMonitorInterfaceFactory;
import de.scoopgmbh.copper.monitoring.server.SpringRemoteServerMain;
import de.scoopgmbh.copper.monitoring.server.monitoring.MonitoringDataAccessQueue;
import de.scoopgmbh.copper.monitoring.server.monitoring.MonitoringDataCollector;
import de.scoopgmbh.copper.monitoring.server.persistent.DerbyMonitoringDbDialect;
import de.scoopgmbh.copper.monitoring.server.persistent.MonitoringDbStorage;
import de.scoopgmbh.copper.monitoring.server.workaround.HistoryCollectorMXBean;
import de.scoopgmbh.copper.monitoring.server.wrapper.MonitoringAdapterProcessingEngine;
import de.scoopgmbh.copper.monitoring.server.wrapper.MonitoringDependencyInjector;
import de.scoopgmbh.copper.persistent.DerbyDbDialect;
import de.scoopgmbh.copper.persistent.PersistentPriorityProcessorPool;
import de.scoopgmbh.copper.persistent.PersistentScottyEngine;
import de.scoopgmbh.copper.persistent.ScottyDBStorage;
import de.scoopgmbh.copper.persistent.StandardJavaSerializer;
import de.scoopgmbh.copper.persistent.txn.CopperTransactionController;
import de.scoopgmbh.copper.spring.SpringDependencyInjector;
import de.scoopgmbh.copper.util.PojoDependencyInjector;
import de.scoopgmbh.copper.wfrepo.FileBasedWorkflowRepository;

public class MonitoringExampleMain {
	
	
	public MonitoringExampleMain start(){
		
		EmbeddedConnectionPoolDataSource40 datasource_default = new EmbeddedConnectionPoolDataSource40();
		datasource_default.setDatabaseName("./build/copperExampleDB;create=true");
		
	
		FileBasedWorkflowRepository wfRepository = new FileBasedWorkflowRepository();
		wfRepository.setTargetDir("build/classes/test");
		wfRepository.setSourceDirs(Arrays.asList("src/main/java/de/scoopgmbh/copper/monitoring/example/workflow"));
		wfRepository.start();
		//wfRepository.shutdown
		

		LoggingStatisticCollector runtimeStatisticsCollector = new LoggingStatisticCollector();
		runtimeStatisticsCollector.start();
		//statisticsCollector.shutdown();
		
		final DerbyDbDialect dbDialect = new DerbyDbDialect();
		dbDialect.setWfRepository(wfRepository);
		dbDialect.setDataSource(datasource_default);
		dbDialect.startup();
		dbDialect.setRuntimeStatisticsCollector(runtimeStatisticsCollector);
		dbDialect.setDbBatchingLatencyMSec(0);
		
		BatcherImpl batcher = new BatcherImpl(3);
		batcher.setStatisticsCollector(runtimeStatisticsCollector);
		
		@SuppressWarnings("rawtypes")
		RetryingTxnBatchRunner batchRunner = new RetryingTxnBatchRunner();
		batchRunner.setDataSource(datasource_default);
		batcher.setBatchRunner(batchRunner);
		batcher.startup();
		//batcherImpl.shutdown();
		
		CopperTransactionController txnController = new CopperTransactionController();
		txnController.setDataSource(datasource_default);
		
		ScottyDBStorage persistentdbStorage = new ScottyDBStorage();
		persistentdbStorage.setTransactionController(txnController);
		persistentdbStorage.setDialect(dbDialect);
		persistentdbStorage.setBatcher(batcher);
		persistentdbStorage.setCheckDbConsistencyAtStartup(true);
	
		
		PersistentPriorityProcessorPool persistentPriorityProcessorPool = new PersistentPriorityProcessorPool("P#DEFAULT",txnController);
		
		PersistentScottyEngine persistentengine = new PersistentScottyEngine();
		persistentengine.setDependencyInjector(new SpringDependencyInjector());		
		persistentengine.setIdFactory(new JdkRandomUUIDFactory());
		persistentengine.setDbStorage(persistentdbStorage);
		persistentengine.setWfRepository(wfRepository);
		persistentengine.setStatisticsCollector(runtimeStatisticsCollector);
		
		DefaultProcessorPoolManager<PersistentPriorityProcessorPool> defaultProcessorPoolManager = new DefaultProcessorPoolManager<PersistentPriorityProcessorPool>();
		defaultProcessorPoolManager.setProcessorPools(Arrays.asList(persistentPriorityProcessorPool));
		defaultProcessorPoolManager.setEngine(persistentengine);
		
		persistentengine.setProcessorPoolManager(defaultProcessorPoolManager);
		//persistentengine.shutdown();
		
		
		BatchingAuditTrail auditTrail = new BatchingAuditTrail();
		auditTrail.setBatcher(batcher);
		auditTrail.setDataSource(datasource_default);
		auditTrail.setMessagePostProcessor(new CompressedBase64PostProcessor());
		try {
			auditTrail.startup();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		auditTrail.asynchLog(1, new Date(), "", "", "", "", "", "detail", "Text");
		
		
		PojoDependencyInjector dependyInjector = new PojoDependencyInjector();
		final MonitoringDataAccessQueue monitoringQueue = new MonitoringDataAccessQueue();
		final MonitoringDataCollector monitoringDataCollector = new MonitoringDataCollector(monitoringQueue);
		MonitoringDependencyInjector monitoringDependencyInjector= new MonitoringDependencyInjector(dependyInjector, monitoringDataCollector);
		persistentengine.setDependencyInjector(monitoringDependencyInjector);
		BillAdapterImpl billAdapterImpl = new BillAdapterImpl();
		billAdapterImpl.initWithEngine(new MonitoringAdapterProcessingEngine(billAdapterImpl,persistentengine,monitoringDataCollector));
		dependyInjector.register("billAdapter", billAdapterImpl);
		persistentengine.startup();
		
		try {
			cleanDB(datasource_default);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		try {
			persistentengine.run("BillWorkflow", "");
		} catch (CopperException e) {
			throw new RuntimeException(e);
		}
			
		List<ProcessingEngineMXBean> engines = new ArrayList<ProcessingEngineMXBean>();
		engines.add(persistentengine);
		DefaultCopperMonitorInterfaceFactory factory = new DefaultCopperMonitorInterfaceFactory(
				new MonitoringDbStorage(txnController,new DerbyMonitoringDbDialect(new StandardJavaSerializer())),
				runtimeStatisticsCollector,
				engines,
				new HistoryCollectorMXBean(){},
				monitoringQueue, 
				true);
	

		new SpringRemoteServerMain(factory,8080,"localhost").start();
		
		return this;
		
	}
	
	public static void main(String[] args) {
		new MonitoringExampleMain().start();
	}
	
	
	void cleanDB(DataSource ds) throws Exception {
		new RetryingTransaction<Void>(ds) {
			@Override
			protected Void execute() throws Exception {
				getConnection().createStatement().execute("DELETE FROM COP_AUDIT_TRAIL_EVENT");
				getConnection().createStatement().execute("DELETE FROM COP_WAIT");
				getConnection().createStatement().execute("DELETE FROM COP_RESPONSE");
				getConnection().createStatement().execute("DELETE FROM COP_QUEUE");
				getConnection().createStatement().execute("DELETE FROM COP_WORKFLOW_INSTANCE");
				getConnection().createStatement().execute("DELETE FROM COP_WORKFLOW_INSTANCE_ERROR");
				return null;
			}
		}.run();
	}

}
