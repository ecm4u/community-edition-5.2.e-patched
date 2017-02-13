/*
 * #%L
 * Alfresco Repository
 * %%
 * Copyright (C) 2005 - 2016 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software. 
 * If the software was purchased under a paid Alfresco license, the terms of 
 * the paid license agreement will prevail.  Otherwise, the software is 
 * provided under the following open source license terms:
 * 
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.repo.search.impl.solr;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.opencmis.dictionary.CMISStrictDictionaryService;
import org.alfresco.repo.admin.RepositoryState;
import org.alfresco.repo.domain.node.NodeDAO;
import org.alfresco.repo.index.shard.Floc;
import org.alfresco.repo.index.shard.ShardInstance;
import org.alfresco.repo.index.shard.ShardRegistry;
import org.alfresco.repo.search.impl.lucene.JSONResult;
import org.alfresco.repo.search.impl.lucene.LuceneQueryParserException;
import org.alfresco.repo.search.impl.lucene.SolrJSONResultSet;
import org.alfresco.repo.search.impl.lucene.SolrJsonProcessor;
import org.alfresco.repo.search.impl.lucene.SolrStatsResult;
import org.alfresco.repo.tenant.TenantService;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.repository.datatype.DefaultTypeConverter;
import org.alfresco.service.cmr.search.BasicSearchParameters;
import org.alfresco.service.cmr.search.FieldHighlightParameters;
import org.alfresco.service.cmr.search.LimitBy;
import org.alfresco.service.cmr.search.PermissionEvaluationMode;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.cmr.search.SearchParameters.FieldFacet;
import org.alfresco.service.cmr.search.SearchParameters.FieldFacetMethod;
import org.alfresco.service.cmr.search.SearchParameters.FieldFacetSort;
import org.alfresco.service.cmr.search.SearchParameters.SortDefinition;
import org.alfresco.service.cmr.search.StatsParameters;
import org.alfresco.service.cmr.security.AuthorityType;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.util.Pair;
import org.alfresco.util.ParameterCheck;
import org.alfresco.util.PropertyCheck;
import org.apache.commons.codec.net.URLCodec;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.solr.common.params.HighlightParams;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.extensions.surf.util.I18NUtil;

import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * @author Andy
 */
public class SolrQueryHTTPClient implements BeanFactoryAware, InitializingBean
{
    static Log s_logger = LogFactory.getLog(SolrQueryHTTPClient.class);

    private NodeService nodeService;

    private PermissionService permissionService;
    
    private NodeDAO nodeDAO;
    
    private TenantService tenantService;
    
    private ShardRegistry shardRegistry;

    private Map<String, String> languageMappings;

    private List<SolrStoreMapping> storeMappings;

    private HashMap<StoreRef, SolrStoreMappingWrapper> mappingLookup = new HashMap<StoreRef, SolrStoreMappingWrapper>();

	private String alternativeDictionary = CMISStrictDictionaryService.DEFAULT;
	
	private RepositoryState repositoryState;

    private BeanFactory beanFactory;
    
    private boolean includeGroupsForRoleAdmin = false;
    
    private int maximumResultsFromUnlimitedQuery = Integer.MAX_VALUE;

    private boolean anyDenyDenies;
    
    private boolean useDynamicShardRegistration = false;
	
    public static final int DEFAULT_SAVEPOST_BUFFER = 4096;
    
    private int defaultUnshardedFacetLimit = 100;
    
    private int defaultShardedFacetLimit = 20;

    public SolrQueryHTTPClient()
    {
    }

    public void init()
    {
        PropertyCheck.mandatory(this, "NodeService", nodeService);
        PropertyCheck.mandatory(this, "PermissionService", permissionService);
        PropertyCheck.mandatory(this, "TenantService", tenantService);
        PropertyCheck.mandatory(this, "LanguageMappings", languageMappings);
        PropertyCheck.mandatory(this, "StoreMappings", storeMappings);
        PropertyCheck.mandatory(this, "RepositoryState", repositoryState);

    }

    public void setAlternativeDictionary(String alternativeDictionary)
    {
        this.alternativeDictionary = alternativeDictionary;
    }

    /**
     * @param repositoryState the repositoryState to set
     */
    public void setRepositoryState(RepositoryState repositoryState)
    {
        this.repositoryState = repositoryState;
    }

    public void setNodeService(NodeService nodeService)
    {
        this.nodeService = nodeService;
    }
    
    /**
     * @param nodeDAO the nodeDao to set
     */
    public void setNodeDAO(NodeDAO nodeDAO)
    {
        this.nodeDAO = nodeDAO;
    }

    public void setPermissionService(PermissionService permissionService)
    {
        this.permissionService = permissionService;
    }
    
    public void setTenantService(TenantService tenantService)
    {
        this.tenantService = tenantService;
    }

    public void setShardRegistry(ShardRegistry shardRegistry)
    {
        this.shardRegistry = shardRegistry;
    }

    public void setUseDynamicShardRegistration(boolean useDynamicShardRegistration)
    {
        this.useDynamicShardRegistration = useDynamicShardRegistration;
    }

    public void setLanguageMappings(Map<String, String> languageMappings)
    {
        this.languageMappings = languageMappings;
    }

    public void setStoreMappings(List storeMappings)
    {
        this.storeMappings = storeMappings;
    }
    
	/**
     * @param includeGroupsForRoleAdmin the includeGroupsForRoleAdmin to set
     */
    public void setIncludeGroupsForRoleAdmin(boolean includeGroupsForRoleAdmin)
    {
        this.includeGroupsForRoleAdmin = includeGroupsForRoleAdmin;
    }
    
    /**
     * @param maximumResultsFromUnlimitedQuery
     *            the maximum number of results to request from an otherwise unlimited query
     */
    public void setMaximumResultsFromUnlimitedQuery(int maximumResultsFromUnlimitedQuery)
    {
        this.maximumResultsFromUnlimitedQuery = maximumResultsFromUnlimitedQuery;
    }

    /**
     * When set, a single DENIED ACL entry for any authority will result in
     * access being denied as a whole. See system property {@code security.anyDenyDenies}
     * 
     * @param anyDenyDenies boolean
     */
    public void setAnyDenyDenies(boolean anyDenyDenies)
    {
        this.anyDenyDenies = anyDenyDenies;
    }
    
    /**
     * @param defaultUnshardedFacetLimit the defaultUnshardedFacetLimit to set
     */
    public void setDefaultUnshardedFacetLimit(int defaultUnshardedFacetLimit)
    {
        this.defaultUnshardedFacetLimit = defaultUnshardedFacetLimit;
    }

    /**
     * @param defaultShardedFacetLimit the defaultShardedFacetLimit to set
     */
    public void setDefaultShardedFacetLimit(int defaultShardedFacetLimit)
    {
        this.defaultShardedFacetLimit = defaultShardedFacetLimit;
    }

    /**
     * Executes a solr query for statistics
     * 
     * @param searchParameters StatsParameters
     * @return SolrStatsResult
     */
    public SolrStatsResult executeStatsQuery(final StatsParameters searchParameters)
    {   
        if(repositoryState.isBootstrapping())
        {
            throw new AlfrescoRuntimeException("SOLR stats queries can not be executed while the repository is bootstrapping");
        }    
         
        try 
        { 
            StoreRef store = extractStoreRef(searchParameters);            
            SolrStoreMappingWrapper mapping = extractMapping(store);
            Locale locale = extractLocale(searchParameters);
            
            Pair<HttpClient, String> httpClientAndBaseUrl = mapping.getHttpClientAndBaseUrl();
            HttpClient httpClient = httpClientAndBaseUrl.getFirst();
            String url = buildStatsUrl(searchParameters, httpClientAndBaseUrl.getSecond(), locale, mapping);
            JSONObject body = buildStatsBody(searchParameters, tenantService.getCurrentUserDomain(), locale);
            
            if(httpClient == null)
            {
                throw new AlfrescoRuntimeException("No http client for store " + store.toString());
            }
            
            return (SolrStatsResult) postSolrQuery(httpClient, url, body, new SolrJsonProcessor<SolrStatsResult>() {

                @Override
                public SolrStatsResult getResult(JSONObject json)
                {
                    return new SolrStatsResult(json, searchParameters.isDateSearch());
                }
                
            });
            
        }
        catch (UnsupportedEncodingException e)
        {
            throw new LuceneQueryParserException("stats", e);
        }
        catch (HttpException e)
        {
            throw new LuceneQueryParserException("stats", e);
        }
        catch (IOException e)
        {
            throw new LuceneQueryParserException("stats", e);
        }
        catch (JSONException e)
        {
            throw new LuceneQueryParserException("stats", e);
        }
    }

    protected String buildStatsUrl(StatsParameters searchParameters, String baseUrl, Locale locale, SolrStoreMappingWrapper mapping) throws UnsupportedEncodingException
    {
        URLCodec encoder = new URLCodec();
        StringBuilder url = new StringBuilder();
        String languageUrlFragment = extractLanguageFragment(searchParameters.getLanguage());
        
        url.append(baseUrl);
        url.append("/").append(languageUrlFragment);
        url.append("?wt=").append(encoder.encode("json", "UTF-8"));
        url.append("&locale=").append(encoder.encode(locale.toString(), "UTF-8"));
        
        url.append(buildSortParameters(searchParameters, encoder));
        
        url.append("&stats=true");
        url.append("&rows=0");
        if (!StringUtils.isBlank(searchParameters.getFilterQuery()))
        {
            url.append("?fq=").append(encoder.encode(searchParameters.getFilterQuery(), "UTF-8")); 
        }

        for(Entry<String, String> entry : searchParameters.getStatsParameters().entrySet())
        {
            url.append("&stats.").append(entry.getKey()).append("=").append(encoder.encode(entry.getValue(), "UTF-8"));
        }
        
        if((mapping != null) && ((searchParameters.getStores().size() > 1) || (mapping.isSharded())))
        {
            boolean requiresSeparator = false;
            url.append("&shards=");
            for(StoreRef storeRef : searchParameters.getStores())
            {
                SolrStoreMappingWrapper storeMapping = extractMapping(storeRef);

                if(requiresSeparator)
                {
                    url.append(',');
                }
                else
                {
                    requiresSeparator = true;
                }

                url.append(storeMapping.getShards());
               
            }
        }
        
        return url.toString();
    }
    

    protected JSONObject buildStatsBody(StatsParameters searchParameters, String tenant, Locale locale) throws JSONException
    {
        JSONObject body = new JSONObject();
        body.put("query", searchParameters.getQuery());
        
        JSONArray tenants = new JSONArray();
        tenants.put(tenant);
        body.put("tenants", tenants);
        
        JSONArray locales = new JSONArray();
        locales.put(locale);
        body.put("locales", locales);
        
        return body;
    }
    
    public ResultSet executeQuery(final SearchParameters searchParameters, String language)
    {   
	    if(repositoryState.isBootstrapping())
	    {
	        throw new AlfrescoRuntimeException("SOLR queries can not be executed while the repository is bootstrapping");
	    }
	    
        try
        {
            StoreRef store = extractStoreRef(searchParameters);
            SolrStoreMappingWrapper mapping = extractMapping(store);
            Pair<HttpClient, String> httpClientAndBaseUrl = mapping.getHttpClientAndBaseUrl();
            HttpClient httpClient = httpClientAndBaseUrl.getFirst();

            URLCodec encoder = new URLCodec();
            StringBuilder url = new StringBuilder();
            url.append(httpClientAndBaseUrl.getSecond());
         
            String languageUrlFragment = extractLanguageFragment(language);
            if(!url.toString().endsWith("/"))
            {
                url.append("/");
            }
            url.append(languageUrlFragment);

            // Send the query in JSON only
            // url.append("?q=");
            // url.append(encoder.encode(searchParameters.getQuery(), "UTF-8"));
            url.append("?wt=").append(encoder.encode("json", "UTF-8"));
            url.append("&fl=").append(encoder.encode("DBID,score", "UTF-8"));

            // Emulate old limiting behaviour and metadata
            final LimitBy limitBy;
            int maxResults = -1;
            if (searchParameters.getMaxItems() >= 0)
            {
                maxResults = searchParameters.getMaxItems();
                limitBy = LimitBy.FINAL_SIZE;
            }
            else if(searchParameters.getLimitBy() == LimitBy.FINAL_SIZE && searchParameters.getLimit() >= 0)
            {
                maxResults = searchParameters.getLimit();
                limitBy = LimitBy.FINAL_SIZE;
            }
            else
            {
                maxResults = searchParameters.getMaxPermissionChecks();
                if (maxResults < 0)
                {
                    maxResults = maximumResultsFromUnlimitedQuery;
                }
                limitBy = LimitBy.NUMBER_OF_PERMISSION_EVALUATIONS;
            }
            url.append("&rows=").append(String.valueOf(maxResults));

            if((searchParameters.getStores().size() > 1) || (mapping.isSharded()))
            {
                boolean requiresSeparator = false;
                url.append("&shards=");
                for(StoreRef storeRef : searchParameters.getStores())
                {
                    SolrStoreMappingWrapper storeMapping = extractMapping(storeRef);

                    if(requiresSeparator)
                    {
                        url.append(',');
                    }
                    else
                    {
                        requiresSeparator = true;
                    }

                    url.append(storeMapping.getShards());

                }
            }

            buildUrlParameters(searchParameters, mapping, encoder, url);

            final String searchTerm = searchParameters.getSearchTerm();
            String spellCheckQueryStr = null;
            if (searchTerm != null && searchParameters.isSpellCheck())
            {
                StringBuilder builder = new StringBuilder();
                builder.append("&spellcheck.q=").append(encoder.encode(searchTerm, "UTF-8"));
                builder.append("&spellcheck=").append(encoder.encode("true", "UTF-8"));
                spellCheckQueryStr = builder.toString();
                url.append(spellCheckQueryStr);
            }

            JSONObject body = new JSONObject();
            body.put("query", searchParameters.getQuery());

            
            // Authorities go over as is - and tenant mangling and query building takes place on the SOLR side

            Set<String> allAuthorisations = permissionService.getAuthorisations();
            boolean includeGroups = includeGroupsForRoleAdmin ? true : !allAuthorisations.contains(PermissionService.ADMINISTRATOR_AUTHORITY);
            
            JSONArray authorities = new JSONArray();
            for (String authority : allAuthorisations)
            {
                if(includeGroups)
                {
                    authorities.put(authority);
                }
                else
                {
                    if(AuthorityType.getAuthorityType(authority) != AuthorityType.GROUP)
                    {
                        authorities.put(authority);
                    }
                }
            }
            body.put("authorities", authorities);
            body.put("anyDenyDenies", anyDenyDenies);
            
            JSONArray tenants = new JSONArray();
            tenants.put(tenantService.getCurrentUserDomain());
            body.put("tenants", tenants);

            JSONArray locales = new JSONArray();
            for (Locale currentLocale : searchParameters.getLocales())
            {
                locales.put(DefaultTypeConverter.INSTANCE.convert(String.class, currentLocale));
            }
            if (locales.length() == 0)
            {
                locales.put(I18NUtil.getLocale());
            }
            body.put("locales", locales);

            JSONArray templates = new JSONArray();
            for (String templateName : searchParameters.getQueryTemplates().keySet())
            {
                JSONObject template = new JSONObject();
                template.put("name", templateName);
                template.put("template", searchParameters.getQueryTemplates().get(templateName));
                templates.put(template);
            }
            body.put("templates", templates);

            JSONArray allAttributes = new JSONArray();
            for (String attribute : searchParameters.getAllAttributes())
            {
                allAttributes.put(attribute);
            }
            body.put("allAttributes", allAttributes);

            body.put("defaultFTSOperator", searchParameters.getDefaultFTSOperator());
            body.put("defaultFTSFieldOperator", searchParameters.getDefaultFTSFieldOperator());
            body.put("queryConsistency", searchParameters.getQueryConsistency());
            if (searchParameters.getMlAnalaysisMode() != null)
            {
                body.put("mlAnalaysisMode", searchParameters.getMlAnalaysisMode().toString());
            }
            body.put("defaultNamespace", searchParameters.getNamespace());

            JSONArray textAttributes = new JSONArray();
            for (String attribute : searchParameters.getTextAttributes())
            {
                textAttributes.put(attribute);
            }
            body.put("textAttributes", textAttributes);

            final int maximumResults = maxResults;  //just needed for the final parameter
            
           
            
            return (ResultSet) postSolrQuery(httpClient, url.toString(), body, new SolrJsonProcessor<SolrJSONResultSet>() {

                @Override
                public SolrJSONResultSet getResult(JSONObject json)
                {
                    return new SolrJSONResultSet(json, searchParameters, nodeService, nodeDAO, limitBy, maximumResults);
                }
                
            }, spellCheckQueryStr);
        }
        catch (UnsupportedEncodingException e)
        {
            throw new LuceneQueryParserException("", e);
        }
        catch (HttpException e)
        {
            throw new LuceneQueryParserException("", e);
        }
        catch (IOException e)
        {
            throw new LuceneQueryParserException("", e);
        }
        catch (JSONException e)
        {
            throw new LuceneQueryParserException("", e);
        }
    }

    /**
     * Builds most of the Url parameters for a Solr Http request.
     * @param searchParameters
     * @param mapping
     * @param encoder
     * @param url
     * @throws UnsupportedEncodingException
     */
    public void buildUrlParameters(SearchParameters searchParameters, SolrStoreMappingWrapper mapping, URLCodec encoder, StringBuilder url)
                throws UnsupportedEncodingException
    {
        Locale locale = extractLocale(searchParameters);
        url.append("&df=").append(encoder.encode(searchParameters.getDefaultFieldName(), "UTF-8"));
        url.append("&start=").append(encoder.encode("" + searchParameters.getSkipCount(), "UTF-8"));

        url.append("&locale=");
        url.append(encoder.encode(locale.toString(), "UTF-8"));
        url.append("&").append(SearchParameters.ALTERNATIVE_DICTIONARY).append("=").append(alternativeDictionary);
        for(String paramName : searchParameters.getExtraParameters().keySet())
        {
            url.append("&").append(paramName).append("=").append(searchParameters.getExtraParameters().get(paramName));
        }
        StringBuffer sortBuffer = buildSortParameters(searchParameters, encoder);
        url.append(sortBuffer);

        if(searchParameters.getPermissionEvaluation() != PermissionEvaluationMode.NONE)
        {
            url.append("&fq=").append(encoder.encode("{!afts}AUTHORITY_FILTER_FROM_JSON", "UTF-8"));
        }

        if(searchParameters.getExcludeTenantFilter() == false)
        {
            url.append("&fq=").append(encoder.encode("{!afts}TENANT_FILTER_FROM_JSON", "UTF-8"));
        }

        if(searchParameters.getFieldFacets().size() > 0 || searchParameters.getFacetQueries().size() > 0)
        {
            url.append("&facet=").append(encoder.encode("true", "UTF-8"));
            for(FieldFacet facet : searchParameters.getFieldFacets())
            {
                url.append("&facet.field=").append(encoder.encode(facet.getField(), "UTF-8"));
                if(facet.getEnumMethodCacheMinDF() != 0)
                {
                    url.append("&").append(encoder.encode("f."+facet.getField()+".facet.enum.cache.minDf", "UTF-8")).append("=").append(encoder.encode(""+facet.getEnumMethodCacheMinDF(), "UTF-8"));
                }
                int facetLimit;
                if(facet.getLimitOrNull() == null)
                {
                    if(mapping.isSharded())
                    {
                        facetLimit = defaultShardedFacetLimit;
                    }
                    else
                    {
                        facetLimit = defaultUnshardedFacetLimit;
                    }
                }
                else
                {
                    facetLimit = facet.getLimitOrNull().intValue();
                }
                url.append("&").append(encoder.encode("f."+facet.getField()+".facet.limit", "UTF-8")).append("=").append(encoder.encode(""+facetLimit, "UTF-8"));
                if(facet.getMethod() != null)
                {
                    url.append("&").append(encoder.encode("f."+facet.getField()+".facet.method", "UTF-8")).append("=").append(encoder.encode(facet.getMethod()== FieldFacetMethod.ENUM ?  "enum" : "fc", "UTF-8"));
                }
                if(facet.getMinCount() != 0)
                {
                    url.append("&").append(encoder.encode("f."+facet.getField()+".facet.mincount", "UTF-8")).append("=").append(encoder.encode(""+facet.getMinCount(), "UTF-8"));
                }
                if(facet.getOffset() != 0)
                {
                    url.append("&").append(encoder.encode("f."+facet.getField()+".facet.offset", "UTF-8")).append("=").append(encoder.encode(""+facet.getOffset(), "UTF-8"));
                }
                if(facet.getPrefix() != null)
                {
                    url.append("&").append(encoder.encode("f."+facet.getField()+".facet.prefix", "UTF-8")).append("=").append(encoder.encode(""+facet.getPrefix(), "UTF-8"));
                }
                if(facet.getSort() != null)
                {
                    url.append("&").append(encoder.encode("f."+facet.getField()+".facet.sort", "UTF-8")).append("=").append(encoder.encode(facet.getSort() == FieldFacetSort.COUNT ? "count" : "index", "UTF-8"));
                }
                if(facet.isCountDocsMissingFacetField() != false)
                {
                    url.append("&").append(encoder.encode("f."+facet.getField()+".facet.missing", "UTF-8")).append("=").append(encoder.encode(""+facet.isCountDocsMissingFacetField(), "UTF-8"));
                }

            }
            for(String facetQuery : searchParameters.getFacetQueries())
            {
                if (!facetQuery.startsWith("{!afts"))
                {
                    facetQuery = "{!afts}"+facetQuery;
                }
                url.append("&facet.query=").append(encoder.encode(facetQuery, "UTF-8"));
            }
        }

        // filter queries
        for(String filterQuery : searchParameters.getFilterQueries())
        {
            if (!filterQuery.startsWith("{!afts"))
            {
                filterQuery = "{!afts}"+filterQuery;
            }
            url.append("&fq=").append(encoder.encode(filterQuery, "UTF-8"));
        }

        // end of field facets

        if (searchParameters.getHighlight() != null)
        {
            url.append("&").append(HighlightParams.HIGHLIGHT+"=true");
            url.append("&"+HighlightParams.HIGHLIGHT+".q=").append(encoder.encode(searchParameters.getSearchTerm(), "UTF-8"));

            if (searchParameters.getHighlight().getSnippetCount() != null)
            {
                url.append("&")
                   .append(HighlightParams.SNIPPETS+"=")
                   .append(searchParameters.getHighlight().getSnippetCount());
            }
            if (searchParameters.getHighlight().getFragmentSize() != null)
            {
                url.append("&")
                   .append(HighlightParams.FRAGSIZE+"=")
                   .append(searchParameters.getHighlight().getFragmentSize());
            }
            if (searchParameters.getHighlight().getMaxAnalyzedChars() != null)
            {
                url.append("&")
                   .append(HighlightParams.MAX_CHARS+"=")
                   .append(searchParameters.getHighlight().getMaxAnalyzedChars());
            }
            if (searchParameters.getHighlight().getMergeContiguous() != null)
            {
                url.append("&")
                   .append(HighlightParams.MERGE_CONTIGUOUS_FRAGMENTS+"=")
                   .append(searchParameters.getHighlight().getMergeContiguous());
            }
            if (searchParameters.getHighlight().getUsePhraseHighlighter() != null)
            {
                url.append("&")
                   .append(HighlightParams.USE_PHRASE_HIGHLIGHTER+"=")
                   .append(searchParameters.getHighlight().getUsePhraseHighlighter());
            }
            if (searchParameters.getHighlight().getPrefix() != null)
            {
                url.append("&")
                   .append(HighlightParams.SIMPLE_PRE+"=")
                   .append(encoder.encode(searchParameters.getHighlight().getPrefix(), "UTF-8"));
            }
            if (searchParameters.getHighlight().getPostfix() != null)
            {
                url.append("&")
                   .append(HighlightParams.SIMPLE_POST+"=")
                   .append(encoder.encode(searchParameters.getHighlight().getPostfix(), "UTF-8"));
            }
            if (searchParameters.getHighlight().getFields() != null && !searchParameters.getHighlight().getFields().isEmpty())
            {
                List<String> fieldNames = new ArrayList<>(searchParameters.getHighlight().getFields().size());
                for (FieldHighlightParameters aField:searchParameters.getHighlight().getFields())
                {
                    ParameterCheck.mandatoryString("highlight field", aField.getField());
                    fieldNames.add(aField.getField());

                    if (aField.getSnippetCount() != null)
                    {
                        url.append("&f.").append(encoder.encode(aField.getField(), "UTF-8"))
                           .append("."+HighlightParams.SNIPPETS+"=")
                           .append(aField.getSnippetCount());
                    }

                    if (aField.getFragmentSize() != null)
                    {
                        url.append("&f.").append(encoder.encode(aField.getField(), "UTF-8"))
                                    .append("."+HighlightParams.FRAGSIZE+"=")
                                    .append(aField.getFragmentSize());
                    }

                    if (aField.getFragmentSize() != null)
                    {
                        url.append("&f.").append(encoder.encode(aField.getField(), "UTF-8"))
                                    .append("."+HighlightParams.FRAGSIZE+"=")
                                    .append(aField.getFragmentSize());
                    }

                    if (aField.getMergeContiguous() != null)
                    {
                        url.append("&f.").append(encoder.encode(aField.getField(), "UTF-8"))
                                    .append("."+HighlightParams.MERGE_CONTIGUOUS_FRAGMENTS+"=")
                                    .append(aField.getMergeContiguous());
                    }

                    if (aField.getPrefix() != null)
                    {
                        url.append("&f.").append(encoder.encode(aField.getField(), "UTF-8"))
                                    .append("."+HighlightParams.SIMPLE_PRE+"=")
                                    .append(encoder.encode(aField.getPrefix(), "UTF-8"));
                    }

                    if (aField.getPostfix() != null)
                    {
                        url.append("&f.").append(encoder.encode(aField.getField(), "UTF-8"))
                                    .append("."+HighlightParams.SIMPLE_POST+"=")
                                    .append(encoder.encode(aField.getPostfix(), "UTF-8"));
                    }
                }
                url.append("&")
                   .append(HighlightParams.FIELDS+"=")
                   .append(encoder.encode(String.join(",", fieldNames), "UTF-8"));
            }
        }
    }

    protected JSONResult postSolrQuery(HttpClient httpClient, String url, JSONObject body, SolrJsonProcessor<?> jsonProcessor)
                throws UnsupportedEncodingException, IOException, HttpException, URIException,
                JSONException
    {
        return postSolrQuery(httpClient, url, body, jsonProcessor, null);
    }

    protected JSONResult postSolrQuery(HttpClient httpClient, String url, JSONObject body, SolrJsonProcessor<?> jsonProcessor, String spellCheckParams)
                throws UnsupportedEncodingException, IOException, HttpException, URIException,
                JSONException
    {
        JSONObject json = postQuery(httpClient, url, body);
        if (spellCheckParams != null)
        {
            SpellCheckDecisionManager manager = new SpellCheckDecisionManager(json, url, body, spellCheckParams);
            if (manager.isCollate())
            {
                json = postQuery(httpClient, manager.getUrl(), body);
            }
            json.put("spellcheck", manager.getSpellCheckJsonValue());
        }

            JSONResult results = jsonProcessor.getResult(json);

            if (s_logger.isDebugEnabled())
            {
                s_logger.debug("Sent :" + url);
                s_logger.debug("   with: " + body.toString());
                s_logger.debug("Got: " + results.getNumberFound() + " in " + results.getQueryTime() + " ms");
            }
            
            return results;
    }

    protected JSONObject postQuery(HttpClient httpClient, String url, JSONObject body) throws UnsupportedEncodingException,
                IOException, HttpException, URIException, JSONException
    {
        PostMethod post = new PostMethod(url);
        if (body.toString().length() > DEFAULT_SAVEPOST_BUFFER)
        {
            post.getParams().setBooleanParameter(HttpMethodParams.USE_EXPECT_CONTINUE, true);
        }
        post.setRequestEntity(new ByteArrayRequestEntity(body.toString().getBytes("UTF-8"), "application/json"));

        try
        {
            httpClient.executeMethod(post);

            if(post.getStatusCode() == HttpStatus.SC_MOVED_PERMANENTLY || post.getStatusCode() == HttpStatus.SC_MOVED_TEMPORARILY)
            {
                Header locationHeader = post.getResponseHeader("location");
                if (locationHeader != null)
                {
                    String redirectLocation = locationHeader.getValue();
                    post.setURI(new URI(redirectLocation, true));
                    httpClient.executeMethod(post);
                }
            }

            if (post.getStatusCode() != HttpServletResponse.SC_OK)
            {
                throw new LuceneQueryParserException("Request failed " + post.getStatusCode() + " " + url.toString());
            }

            Reader reader = new BufferedReader(new InputStreamReader(post.getResponseBodyAsStream(), post.getResponseCharSet()));
            // TODO - replace with streaming-based solution e.g. SimpleJSON ContentHandler
            JSONObject json = new JSONObject(new JSONTokener(reader));

            if (json.has("status"))
            {
                JSONObject status = json.getJSONObject("status");
                if (status.getInt("code") != HttpServletResponse.SC_OK)
                {
                    throw new LuceneQueryParserException("SOLR side error: " + status.getString("message"));
                }
            }
            return json;
        }
        finally
        {
            post.releaseConnection();
        }
    }

    private StringBuffer buildSortParameters(BasicSearchParameters searchParameters, URLCodec encoder)
                throws UnsupportedEncodingException
    {
        StringBuffer sortBuffer = new StringBuffer();
        for (SortDefinition sortDefinition : searchParameters.getSortDefinitions())
        {
            if (sortBuffer.length() == 0)
            {
                sortBuffer.append("&sort=");
            }
            else
            {
                sortBuffer.append(encoder.encode(", ", "UTF-8"));
            }
            // MNT-8557 fix, manually replace ' ' with '%20'
            // The sort can be different, see MNT-13742
            switch (sortDefinition.getSortType())
            {
                case DOCUMENT:
                    sortBuffer.append(encoder.encode("_docid_", "UTF-8")).append(encoder.encode(" ", "UTF-8"));
                    break;
                case SCORE:
                    sortBuffer.append(encoder.encode("score", "UTF-8")).append(encoder.encode(" ", "UTF-8"));
                    break;
                case FIELD:
                default:
                    sortBuffer.append(encoder.encode(sortDefinition.getField().replaceAll(" ", "%20"), "UTF-8")).append(encoder.encode(" ", "UTF-8"));
                    break;
            }
	        if (sortDefinition.isAscending())
            {
                sortBuffer.append(encoder.encode("asc", "UTF-8"));
            }
            else
            {
                sortBuffer.append(encoder.encode("desc", "UTF-8"));
            }

        }
        return sortBuffer;
    }

    private Locale extractLocale(BasicSearchParameters searchParameters)
    {
        Locale locale = I18NUtil.getLocale();
        if (searchParameters.getLocales().size() > 0)
        {
            locale = searchParameters.getLocales().get(0);
        }
        return locale;
    }

    private String extractLanguageFragment(String language)
    {
        String languageUrlFragment = languageMappings.get(language);
        if (languageUrlFragment == null)
        {
            throw new AlfrescoRuntimeException("No solr query support for language " + language);
        }
        return languageUrlFragment;
    }

    private SolrStoreMappingWrapper extractMapping(StoreRef store)
    {
        if((shardRegistry != null) && useDynamicShardRegistration)
        {
            SearchParameters sp = new SearchParameters();
            sp.addStore(store);
            List<ShardInstance> slice = shardRegistry.getIndexSlice(sp);
            if((slice == null) || (slice.size() == 0))
            {
                s_logger.error("No available shards for solr query of store " + store + " - trying non-dynamic configuration");
                SolrStoreMappingWrapper mappings = mappingLookup.get(store);
                if (mappings == null)
                {
                    throw new LuceneQueryParserException("No solr query support for store " + store);
                }
                return mappings;
            }
            return DynamicSolrStoreMappingWrapperFactory.wrap(slice, beanFactory);
        }
        else
        {
            SolrStoreMappingWrapper mappings = mappingLookup.get(store);

            if (mappings == null)
            {
                throw new LuceneQueryParserException("No solr query support for store " + store);
            }
            return mappings;
        }
    }

    private StoreRef extractStoreRef(BasicSearchParameters searchParameters)
    {
        if (searchParameters.getStores().size() == 0)
        {
            throw new AlfrescoRuntimeException("No store for query");
        }
        
        StoreRef store = searchParameters.getStores().get(0);
        return store;
    }

    /* (non-Javadoc)
     * @see org.springframework.beans.factory.BeanFactoryAware#setBeanFactory(org.springframework.beans.factory.BeanFactory)
     */
    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException
    {
        this.beanFactory = beanFactory;
    }

    /* (non-Javadoc)
     * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
     */
    @Override
    public void afterPropertiesSet() throws Exception
    {
        mappingLookup.clear();
        for(SolrStoreMapping mapping : storeMappings)
        {
            mappingLookup.put(mapping.getStoreRef(), new ExplicitSolrStoreMappingWrapper(mapping, beanFactory));
        }
    }

    /**
     * @param storeRef
     * @param handler
     * @param params
     * @return
     */
    public JSONObject execute(StoreRef storeRef, String handler, HashMap<String, String> params)
    {       
        try
        {
            SolrStoreMappingWrapper mapping = extractMapping(storeRef);
            
            URLCodec encoder = new URLCodec();
            StringBuilder url = new StringBuilder();
         
            Pair<HttpClient, String> httpClientAndBaseUrl = mapping.getHttpClientAndBaseUrl();
            HttpClient httpClient = httpClientAndBaseUrl.getFirst();

            
            for (String key : params.keySet())
            {
                String value = params.get(key);
                if (url.length() == 0)
                {
                    url.append(httpClientAndBaseUrl.getSecond());
                    
                    if(!handler.startsWith("/"))
                    {
                        url.append("/");
                    }
                    url.append(handler);
                    url.append("?");
                    url.append(encoder.encode(key, "UTF-8"));
                    url.append("=");
                    url.append(encoder.encode(value, "UTF-8"));
                }
                else
                {
                    url.append("&");
                    url.append(encoder.encode(key, "UTF-8"));
                    url.append("=");
                    url.append(encoder.encode(value, "UTF-8"));
                }

            }
            
            if(mapping.isSharded())
            {
                url.append("&shards=");
                url.append(mapping.getShards());
            }

            // PostMethod post = new PostMethod(url.toString());
            GetMethod get = new GetMethod(url.toString());

            try
            {
                httpClient.executeMethod(get);

                if (get.getStatusCode() == HttpStatus.SC_MOVED_PERMANENTLY || get.getStatusCode() == HttpStatus.SC_MOVED_TEMPORARILY)
                {
                    Header locationHeader = get.getResponseHeader("location");
                    if (locationHeader != null)
                    {
                        String redirectLocation = locationHeader.getValue();
                        get.setURI(new URI(redirectLocation, true));
                        httpClient.executeMethod(get);
                    }
                }

                if (get.getStatusCode() != HttpServletResponse.SC_OK)
                {
                    throw new LuceneQueryParserException("Request failed " + get.getStatusCode() + " " + url.toString());
                }

                Reader reader = new BufferedReader(new InputStreamReader(get.getResponseBodyAsStream()));
                // TODO - replace with streaming-based solution e.g. SimpleJSON ContentHandler
                JSONObject json = new JSONObject(new JSONTokener(reader));
                return json;
            }
            finally
            {
                get.releaseConnection();
            }
        }
        catch (UnsupportedEncodingException e)
        {
            throw new LuceneQueryParserException("", e);
        }
        catch (HttpException e)
        {
            throw new LuceneQueryParserException("", e);
        }
        catch (IOException e)
        {
            throw new LuceneQueryParserException("", e);
        }
        catch (JSONException e)
        {
            throw new LuceneQueryParserException("", e);
        }
    }

    /**
     * @return
     */
    public boolean isSharded()
    {
        if((shardRegistry != null) && useDynamicShardRegistration)
        {
            for( Floc floc : shardRegistry.getFlocs().keySet())
            {
                if(floc.getNumberOfShards() > 1)
                {
                    return true;
                }
            }
            return false;
        
        }
        else
        {
            for(SolrStoreMappingWrapper mapping : mappingLookup.values())
            {
                if(mapping.isSharded())
                {
                    return true;
                }
            }
            return false;
        }
        
    }
}
