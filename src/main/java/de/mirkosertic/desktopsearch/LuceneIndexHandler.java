/*
 * FreeDesktopSearch - A Search Engine for your Desktop
 * Copyright (C) 2013 Mirko Sertic
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, see <http://www.gnu.org/licenses/>.
 */
package de.mirkosertic.desktopsearch;

import org.apache.commons.codec.EncoderException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.codec.net.URLCodec;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.tika.utils.DateUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

class LuceneIndexHandler {

    private static final Logger LOGGER = Logger.getLogger(LuceneIndexHandler.class);

    private static final int NUMBER_OF_FRAGMENTS = 5;

    private final Configuration configuration;
    private final PreviewProcessor previewProcessor;
    private final SolrEmbedded solrEmbedded;
    private final SolrClient solrClient;

    public LuceneIndexHandler(final Configuration aConfiguration, final PreviewProcessor aPreviewProcessor) throws IOException {
        previewProcessor = aPreviewProcessor;
        configuration = aConfiguration;

        final File theIndexDirectory = new File(aConfiguration.getConfigDirectory(), "index");
        theIndexDirectory.mkdirs();

        solrEmbedded = new SolrEmbedded(new SolrEmbedded.Config(theIndexDirectory));
        solrClient = solrEmbedded.solrClient();
    }

    public void crawlingStarts() throws IOException {
    }

    public void addToIndex(final String aLocationId, final Content aContent) throws IOException {

        final SupportedLanguage theLanguage = aContent.getLanguage();

        final SolrInputDocument theDocument = new SolrInputDocument();
        theDocument.setField(IndexFields.UNIQUEID, aContent.getFileName());
        theDocument.setField(IndexFields.LOCATIONID, aLocationId);
        theDocument.setField(IndexFields.CONTENTMD5, DigestUtils.md5Hex(aContent.getFileContent()));
        theDocument.setField(IndexFields.LOCATIONID, aLocationId);
        theDocument.setField(IndexFields.FILESIZE, Long.toString(aContent.getFileSize()));
        theDocument.setField(IndexFields.LASTMODIFIED, Long.toString(aContent.getLastModified()));
        theDocument.setField(IndexFields.LANGUAGE, theLanguage.name());

        final StringBuilder theContentAsString = new StringBuilder(aContent.getFileContent());

        aContent.getMetadata().forEach(theEntry -> {
            if (!StringUtils.isEmpty(theEntry.key)) {
                final Object theValue = theEntry.value;
                if (theValue instanceof String) {
                    final String theStringValue = (String) theValue;
                    if (!StringUtils.isEmpty(theStringValue)) {
                        theDocument.setField("attr_" + theEntry.key, theStringValue);
                    }
                }
                if (theValue instanceof Date) {
                    final Date theDateValue = (Date) theValue;
                    final Calendar theCalendar = GregorianCalendar.getInstance(DateUtils.UTC, Locale.US);
                    theCalendar.setTime(theDateValue);

                    // Full-Path
                    {
                        final String thePathInfo = String.format(
                                "%04d/%02d/%02d",
                                theCalendar.get(Calendar.YEAR),
                                theCalendar.get(Calendar.MONTH) + 1,
                                theCalendar.get(Calendar.DAY_OF_MONTH));

                        theDocument.setField("attr_" + theEntry.key+"-year-month-day", thePathInfo);
                    }
                    // Year
                    {
                        final String thePathInfo = String.format(
                                "%04d",
                                theCalendar.get(Calendar.YEAR));

                        theDocument.setField("attr_" + theEntry.key+"-year", thePathInfo);
                    }
                    // Year-month
                    {
                        final String thePathInfo = String.format(
                                "%04d/%02d",
                                theCalendar.get(Calendar.YEAR),
                                theCalendar.get(Calendar.MONTH) + 1);

                        theDocument.setField("attr_" + theEntry.key+"-year-month", thePathInfo);
                    }

                }
            }
        });

        theDocument.setField(IndexFields.CONTENT, theContentAsString.toString());

        try {
            solrClient.add(theDocument);
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    public void removeFromIndex(final String aFileName) throws IOException {
        try {
            solrClient.deleteById(aFileName);
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    public void shutdown() {
        try {
            solrEmbedded.shutdown();
        } catch (final Exception e) {
            LOGGER.error("Error while closing IndexWriter", e);
        }
    }

    public UpdateCheckResult checkIfModified(final String aFilename, final long aLastModified) throws IOException {

        final Map<String, Object> theParams = new HashMap<>();
        theParams.put("q", IndexFields.UNIQUEID + ":" + ClientUtils.escapeQueryChars(aFilename));

        try {
            final QueryResponse theQueryResponse = solrClient.query(new SearchMapParams(theParams));
            if (theQueryResponse.getResults() == null || theQueryResponse.getResults().isEmpty()) {
                // Nothing in Index, hence mark it as updated
                return UpdateCheckResult.UPDATED;
            }
            final SolrDocument theDocument = theQueryResponse.getResults().get(0);

            final long theStoredLastModified = Long.valueOf((String) theDocument.getFieldValue(IndexFields.LASTMODIFIED));
            if (theStoredLastModified != aLastModified) {
                return UpdateCheckResult.UPDATED;
            }
            return UpdateCheckResult.UNMODIFIED;
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    private String encode(final String aValue) {
        final URLCodec theURLCodec = new URLCodec();
        try {
            return theURLCodec.encode(aValue);
        } catch (final EncoderException e) {
            return null;
        }
    }

    private long indexSize() throws IOException, SolrServerException {
        final SolrQuery q = new SolrQuery("*:*");
        q.setRows(0);  // don't actually request any data
        final QueryResponse theResponse = solrClient.query(q);
        if (theResponse.getResults() != null) {
            return theResponse.getResults().getNumFound();
        }
        return 0;
    }

    public QueryResult performQuery(final String aQueryString, final String aBacklink, final String aBasePath, final Configuration aConfiguration, final Map<String, String> aDrilldownFields) throws IOException {

        final Map<String, Object> theParams = new HashMap<>();
        theParams.put("defType", "google");
        theParams.put("q", aQueryString);
        theParams.put("fl", "*,score");
        theParams.put("rows", "" + configuration.getNumberOfSearchResults());
        theParams.put("facet", "true");
        theParams.put("facet.field", new String[] {IndexFields.LANGUAGE, "attr_author", "attr_last-modified-year", "attr_" + IndexFields.EXTENSION});
        theParams.put("hl", "true");
        theParams.put("hl.method", "unified");
        theParams.put("hl.fl", IndexFields.CONTENT);
        theParams.put("hl.snippets", "" + NUMBER_OF_FRAGMENTS);
        theParams.put("hl.fragsize", "100");

        if (aDrilldownFields != null) {
            final List<String> theFilters = new ArrayList<>();
            for (final Map.Entry<String, String> theField : aDrilldownFields.entrySet()) {
                theFilters.add(theField.getKey()+":"+ClientUtils.escapeQueryChars(theField.getValue()));
            }
            if (!theFilters.isEmpty()) {
                theParams.put("fq", theFilters.toArray(new String[theFilters.size()]));
            }
        }

        if (aConfiguration.isShowSimilarDocuments()) {
            theParams.put("mlt", "true");
            theParams.put("mlt.count", "5");
            theParams.put("mlt.fl", IndexFields.CONTENT);
        }

        try {
            final long theStartTime = System.currentTimeMillis();
            final QueryResponse theQueryResponse = solrClient.query(new SearchMapParams(theParams));

            final List<QueryResultDocument> theDocuments = new ArrayList<>();
            if (theQueryResponse.getResults() != null) {
                for (int i = 0; i < theQueryResponse.getResults().size(); i++) {
                    final SolrDocument theSolrDocument = theQueryResponse.getResults().get(i);

                    final String theFileName = (String) theSolrDocument.getFieldValue(IndexFields.UNIQUEID);
                    final long theStoredLastModified = Long.valueOf((String) theSolrDocument.getFieldValue(IndexFields.LASTMODIFIED));

                    final int theNormalizedScore = (int) (
                            ((float) theSolrDocument.getFieldValue("score")) / theQueryResponse.getResults().getMaxScore() * 5);

                    final StringBuffer theHighlight = new StringBuffer();
                    final Map<String, List<String>> theHighlightPhrases = theQueryResponse.getHighlighting().get(theFileName);
                    if (theHighlightPhrases != null) {
                        final List<String> theContentSpans = theHighlightPhrases.get(IndexFields.CONTENT);
                        if (theContentSpans != null) {
                            for (final String thePhrase : theContentSpans) {
                                if (theHighlight.length() > 0) {
                                    theHighlight.append(" ... ");
                                }
                                theHighlight.append(thePhrase.trim());
                            }
                        } else {
                            LOGGER.warn("No highligting for " + theFileName);
                        }
                    }

                    final File theFileOnDisk = new File(theFileName);
                    if (theFileOnDisk.exists()) {

                        final boolean thePreviewAvailable = previewProcessor.previewAvailableFor(theFileOnDisk);

                        final QueryResultDocument theDocument = new QueryResultDocument(i, theFileName, theHighlight.toString().trim(),
                                theStoredLastModified, theNormalizedScore, theFileName, thePreviewAvailable);

                        if (configuration.isShowSimilarDocuments()) {
                            final SolrDocumentList theMoreLikeThisDocuments = theQueryResponse.getMoreLikeThis().get(theFileName);
                            if (theMoreLikeThisDocuments != null) {
                                for (int j = 0; j < theMoreLikeThisDocuments.size(); j++) {
                                    final SolrDocument theMLt = theMoreLikeThisDocuments.get(j);
                                    theDocument.addSimilarFile(((String) theMLt.getFieldValue(IndexFields.UNIQUEID)));
                                }
                            }
                        }

                        theDocuments.add(theDocument);

                    } else {

                        // Document can be deleted, as it is no longer on the hard drive
                        solrClient.deleteById(theFileName);
                    }
                }
            }

            final long theIndexSize = indexSize();

            final long theDuration = System.currentTimeMillis() - theStartTime;

            final List<FacetDimension> theDimensions = new ArrayList<>();
            fillFacet(IndexFields.LANGUAGE, "Language", aBasePath, theQueryResponse, theDimensions, t -> SupportedLanguage.valueOf(t).toLocale().getDisplayName());
            fillFacet("attr_author", "Author", aBasePath, theQueryResponse, theDimensions, t -> t);
            fillFacet("attr_last-modified-yea", "Last modified", aBasePath, theQueryResponse, theDimensions, t -> t);
            fillFacet("attr_" + IndexFields.EXTENSION, "File type", aBasePath, theQueryResponse, theDimensions, t -> t);

            return new QueryResult(theDuration, theDocuments, theDimensions, theIndexSize, aBacklink);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void fillFacet(final String aFacetField, final String aFacetDisplayLabel, final String aBacklink, final QueryResponse aQueryResponse, final List<FacetDimension> aDimensions,
            final Function<String, String> aConverter) {
        final FacetField theFacet = aQueryResponse.getFacetField(aFacetField);
        if (theFacet != null) {
            final List<Facet> theFacets = new ArrayList<>();
            for (final FacetField.Count theCount : theFacet.getValues()) {
                if (theCount.getCount() > 0) {
                    final String theName = theCount.getName().trim();
                    if (theName.length() > 0) {
                        theFacets.add(new Facet(aConverter.apply(theName), theCount.getCount(),
                                aBacklink + "/" + encode(
                                        FacetSearchUtils.encode(aFacetField, theCount.getName()))));
                    }
                }
            }
            if (!theFacets.isEmpty()) {
                aDimensions.add(new FacetDimension(aFacetDisplayLabel, theFacets));
            }
        }
    }

    public Suggestion[] findSuggestionTermsFor(final String aTerm) throws IOException {

        final Map<String, Object> theParams = new HashMap<>();
        theParams.put("fxsuggest.enabled", "true");
        theParams.put("fxsuggest.q", aTerm);
        theParams.put("fxsuggest.slop", Integer.toString(configuration.getSuggestionSlop()));
        theParams.put("fxsuggest.inorder", Boolean.toString(configuration.isSuggestionInOrder()));
        theParams.put("fxsuggest.numbersuggest", Integer.toString(configuration.getNumberOfSuggestions()));

        try {
            final QueryResponse theQueryResponse = solrClient.query(new SearchMapParams(theParams));

            final NamedList theSuggestions = (NamedList) theQueryResponse.getResponse().get("fxsuggest");
            final List<Suggestion> theResult = new ArrayList<>();
            for (int i=0;i<theSuggestions.size();i++) {
                final Map theEntry = (Map) theSuggestions.get(Integer.toString(i));
                final String theLabel = (String) theEntry.get("label");
                final String theValue = (String) theEntry.get("value");
                theResult.add(new Suggestion(theLabel, theValue));
            }

            return theResult.toArray(new Suggestion[theResult.size()]);

        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    public File getFileOnDiskForDocument(final String aUniqueID) throws IOException {
        return new File(aUniqueID);
    }
}