package com.search;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.IndexSearcher;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.util.RTimer;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrConfig;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.handler.component.*;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocList;
import org.apache.solr.search.ReturnFields;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.SolrPluginUtils;
import org.apache.solr.util.plugin.PluginInfoInitialized;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;


/**
 *
 * Refer SOLR-281
 *
 */
public class MySearchHandlerTest extends RequestHandlerBase implements SolrCoreAware , PluginInfoInitialized
{
	static final String THIS_COMPONENT = "MySearchHandlerTest";
	static final String INIT_COMPONENTS = "components";
	static final String INIT_FIRST_COMPONENTS = "first-components";
	static final String INIT_LAST_COMPONENTS = "last-components";

	protected static Logger log = LoggerFactory.getLogger(MySearchHandlerTest.class);

	protected List<SearchComponent> components = null;
	private ShardHandlerFactory shardHandlerFactory ;
	private PluginInfo shfInfo;

	protected List<String> getDefaultComponents()
	{
		ArrayList<String> names = new ArrayList<String>(6);
		names.add( QueryComponent.COMPONENT_NAME );
		//	names.add( FacetComponent.COMPONENT_NAME );
		//	names.add( MoreLikeThisComponent.COMPONENT_NAME );
		//	names.add( HighlightComponent.COMPONENT_NAME );
		//	names.add( StatsComponent.COMPONENT_NAME );
		//	names.add( DebugComponent.COMPONENT_NAME );
		return names;
	}

	@Override
	public void init(PluginInfo info) {
		init(info.initArgs);
		for (PluginInfo child : info.children) {
			if("shardHandlerFactory".equals(child.type)){
				this.shfInfo = child;
				break;
			}
		}
	}

	/**
	 * Initialize the components based on name.  Note, if using <code>INIT_FIRST_COMPONENTS</code> or <code>INIT_LAST_COMPONENTS</code>,
	 * then the {@link DebugComponent} will always occur last.  If this is not desired, then one must explicitly declare all components using
	 * the <code>INIT_COMPONENTS</code> syntax.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public void inform(SolrCore core)
	{
		Object declaredComponents = initArgs.get(INIT_COMPONENTS);
		List<String> first = (List<String>) initArgs.get(INIT_FIRST_COMPONENTS);
		List<String> last  = (List<String>) initArgs.get(INIT_LAST_COMPONENTS);

		List<String> list = null;
		boolean makeDebugLast = true;
		if( declaredComponents == null ) {
			// Use the default component list
			list = getDefaultComponents();

			if( first != null ) {
				List<String> clist = first;
				clist.addAll( list );
				list = clist;
			}

			if( last != null ) {
				list.addAll( last );
			}
		}
		else {
			list = (List<String>)declaredComponents;
			if( first != null || last != null ) {
				throw new SolrException( SolrException.ErrorCode.SERVER_ERROR,
						"First/Last components only valid if you do not declare 'components'");
			}
			makeDebugLast = false;
		}

		// Build the component list
		components = new ArrayList<SearchComponent>( list.size() );
		DebugComponent dbgCmp = null;
		for(String c : list){
			SearchComponent comp = core.getSearchComponent( c );
			if (comp instanceof DebugComponent && makeDebugLast == true){
				dbgCmp = (DebugComponent) comp;
			} else {
				components.add(comp);
				log.debug("Adding  component:"+comp);
			}
		}
		if (makeDebugLast == true && dbgCmp != null){
			components.add(dbgCmp);
			log.debug("Adding  debug component:" + dbgCmp);
		}
		if(shfInfo ==null) {
			shardHandlerFactory = core.getCoreDescriptor().getCoreContainer().getShardHandlerFactory();
		} else {
			shardHandlerFactory = core.createInitInstance(shfInfo, ShardHandlerFactory.class, null, null);
			core.addCloseHook(new CloseHook() {
				@Override
				public void preClose(SolrCore core) {
					shardHandlerFactory.close();
				}
				@Override
				public void postClose(SolrCore core) {
				}
			});
		}

	}

	public List<SearchComponent> getComponents() {
		return components;
	}


	@Override
	public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception
	{
		// int sleep = req.getParams().getInt("sleep",0);
		// if (sleep > 0) {log.error("SLEEPING for " + sleep);  Thread.sleep(sleep);}

		/*** Pre-processing of the Query by REGEX starts here --------------***/
		SolrParams originalParams = req.getOriginalParams();
		SolrParams psuedoParams = req.getParams();	// These psuedoParams keep changing
		if (originalParams.get(CommonParams.Q) != null) {
			String finalQuery;


			String originalQuery = originalParams.get(CommonParams.Q);

			rsp.add("Original query", originalQuery);

			/*** Arafath's code to prepare query starts here
			 * The query should be in the following format ->
			 * Example : 
			 * 		Original Query: "Which musical object did russ conway play"
			 *		Temporary Query : "relations:instrument AND entity:enty" // Generate the relation
			 * 		Final Query : "name:"russ conway" AND occupation:musician"
			 */

			String tempQuery = "relations:instrumental AND entity:enty";
			rsp.add("Temporary query", tempQuery);
			String desiredField = null;
			Set <String> fieldSet = null;
			SchemaField keyField = null;

			NamedList tempparamsList = req.getParams().toNamedList();
			tempparamsList.setVal(tempparamsList.indexOf(CommonParams.Q, 0), tempQuery);
			psuedoParams = SolrParams.toSolrParams(tempparamsList);
	//		if (psuedoParams.get(CommonParams.SORT) == null) {
	//			tempparamsList.add(CommonParams.SORT, "score desc");
	//		} else {
	//			tempparamsList.setVal(tempparamsList.indexOf(CommonParams.SORT, 0), "score desc");
	//		}

			SolrQueryRequest firstreq = new LocalSolrQueryRequest(req.getCore(), tempparamsList);
			SolrQueryResponse firstrsp = new SolrQueryResponse();
			firstrsp.setAllValues(rsp.getValues());
			ResponseBuilder firstrb = new ResponseBuilder(firstreq,firstrsp,components);

			for (SearchComponent c : components) {
				c.prepare(firstrb);
				c.process(firstrb);
			}
			rsp.add("response", firstrb.getResults().docList);
/***
			DocList docs = firstrb.getResults().docList;
			if (docs == null || docs.size() == 0) {
				log.debug("No results");
			} else {
				fieldSet = new HashSet <String> ();
				keyField = firstrb.req.getCore().getLatestSchema().getUniqueKeyField();
				if (keyField != null) {
					fieldSet.add(keyField.getName());
				}
				fieldSet.add("fieldid");
				fieldSet.add("relations");
				fieldSet.add("entity");
				fieldSet.add("count");
				NamedList docresults = new NamedList();
				DocIterator iterator = docs.iterator();
				Document doc;
				int docScore = 0;
				rsp.add("doc retrieved ", docs.size());
				for (int i=0; i<docs.size(); i++) {
					try {
						int docid = iterator.nextDoc();
						doc = firstrb.req.getSearcher().doc(docid, fieldSet);
						if (Integer.parseInt(doc.get("count")) > docScore) {
							docScore = Integer.parseInt(doc.get("count"));
							desiredField = doc.get("fieldid");
						}
						docresults.add(String.valueOf(docid), doc);
					} catch (IOException ex) {
						java.util.logging.Logger.getLogger(CustomQueryComponent.class.getName()).log(Level.SEVERE, null,ex);
					}
				}
				fieldSet.clear();
				rsp.add("Intermediate results", docresults);
				if (desiredField != null) {
					rsp.add("Required Field", desiredField);
				}
			} ***/

			firstreq.close();

			/*** Final Phase starts here ***/
	/***	finalQuery = "name:\"russ conway\" AND occupation:musician";
			NamedList finalparamsList = req.getParams().toNamedList();
			finalparamsList.setVal(finalparamsList.indexOf(CommonParams.Q, 0), finalQuery);
			psuedoParams = SolrParams.toSolrParams(finalparamsList);
			if (psuedoParams.get(CommonParams.SORT) == null) {
				finalparamsList.add(CommonParams.SORT, "score desc");
			} else {
				finalparamsList.setVal(finalparamsList.indexOf(CommonParams.SORT, 0), "score desc");
			}	
		//	if (desiredField != null) {
		//		if (psuedoParams.get(CommonParams.FL) != null) {
		//			finalparamsList.setVal(finalparamsList.indexOf(CommonParams.FL, 0), desiredField);
		//		} else {
		//			finalparamsList.add(CommonParams.FL, desiredField);
		//		}
		//	}

			SolrQueryRequest finalreq = new LocalSolrQueryRequest(req.getCore(), finalparamsList);
			rsp.add("Final Query", finalreq.getParams().get(CommonParams.Q));
			ResponseBuilder rb = new ResponseBuilder(finalreq,rsp,components);
			for (SearchComponent c : components) {
				c.prepare(rb);
				c.process(rb);
			} ***/
/*** testing
			DocList finaldocs = rb.getResults().docList;
			if (finaldocs == null || finaldocs.size() == 0) {
				log.debug("No results");
			} else {
				keyField = rb.req.getCore().getLatestSchema().getUniqueKeyField();
				if (keyField != null) {
					fieldSet.add(keyField.getName());
				}
				if (desiredField != null) {
					fieldSet.add(desiredField);
				}
				fieldSet.add("name");
				NamedList finaldocresults = new NamedList();
				DocIterator finaliterator = finaldocs.iterator();
				Document finaldoc;
				rsp.add("finaldocs retrieved ", finaldocs.size());
				for (int i=0; i<docs.size(); i++) {
					try {
						if (finaliterator.hasNext()) {
							int finaldocid = finaliterator.nextDoc();
							finaldoc = rb.req.getSearcher().doc(finaldocid, fieldSet);
							finaldocresults.add(String.valueOf(finaldocid), finaldoc);
						}
					} catch (IOException ex) {
						java.util.logging.Logger.getLogger(MySearchHandler.class.getName()).log(Level.SEVERE, null,ex);
					}
				}
				rsp.add("final results", finaldocresults);
			} ***/
		//	finalreq.close(); 
		} else {
			throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Need to give at least one word as query!");
		}
	}

	//////////////////////// SolrInfoMBeans methods //////////////////////

	@Override
	public String getDescription() {
		StringBuilder sb = new StringBuilder();
		sb.append("Search using components: ");
		if( components != null ) {
			for(SearchComponent c : components){
				sb.append(c.getName());
				sb.append(",");
			}
		}
		return sb.toString();
	}

	@Override
	public String getSource() {
		return "$URL: https://svn.apache.org/repos/asf/lucene/dev/branches/lucene_solr_4_6/solr/core/src/java/org/apache/solr/handler/component/SearchHandler.java $";
	}
}


