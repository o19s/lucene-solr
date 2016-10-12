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
package org.apache.solr.search;


import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.lucene.queries.function.FunctionQuery;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.queries.function.valuesource.QueryValueSource;
import org.apache.lucene.search.Query;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.transform.DocTransformer;
import org.apache.solr.response.transform.DocTransformers;
import org.apache.solr.response.transform.RenameFieldTransformer;
import org.apache.solr.response.transform.ScoreAugmenter;
import org.apache.solr.response.transform.TransformerFactory;
import org.apache.solr.response.transform.ValueSourceAugmenter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of {@link ReturnFields}.
 * Encapsulates parsing logic of {@link CommonParams#FL} parameter and provides methods
 * for indicating if a given field should be included in response or not.
 */
public class SolrReturnFields extends ReturnFields {

  private final List<String> globs = new ArrayList<>(1);

  public final static String SCORE = "score";
  public final static char WILDCARD = '*';
  public final static char QUESTION_MARK = '?';
  public final static char OPEN_BRACKET = '[';
  public final static char CLOSE_BRACKET = ']';
  public final static char OPEN_PARENTHESIS = '(';
  public final static char CLOSE_PARENTHESIS = ')';
  public final static char LEFT_BRACE = '{';
  public final static char RIGHT_BRACE = '}';

  public final static char HYPHEN = '-';
  public final static char QUOTE = '\'';
  public final static char DQUOTE = '\"';
  public final static char COLON = ':';
  public final static char POUND_SIGN = '#';
  public final static char DOT = '.';
  public final static char PLUS = '+';

  public final static String ALIAS_VALUE_SEPARATOR = Character.toString(COLON);
  public final static String ALL_FIELDS = Character.toString(WILDCARD);


  // The list of explicitly requested fields
  // Order is important for CSVResponseWriter
  private Set<String> requestedFieldNames;

  protected DocTransformer transformer;
  protected boolean _wantsScore = false;
  protected boolean _wantsAllFields = false;
  protected Map<String,String> renameFields = Collections.emptyMap();

  // Parser state (initial state is "detecting token type").
  private ParserState currentState;
  private boolean wantsAllFields;

  private Set<String> luceneFieldNames;
  private Set<String> inclusions;
  private Set<String> exclusions;
  private Set<String> inclusionGlobs;
  private Set<String> exclusionGlobs;
  private Map<String, List<String>> aliases;

  private final Map<String, Boolean> cache = new HashMap<>();

  /**
   * Builds a new {@link SolrReturnFields} with no data.
   * Basically it returns everything (i.e. *).
   */
  public SolrReturnFields() {
    this((String[]) null, null);
  }

  /**
   * Builds a new {@link SolrReturnFields} with the current request.
   * The {@link CommonParams#FL} is used to get the field list(s).
   *
   * @param request the current request.
   */
  public SolrReturnFields(final SolrQueryRequest request) {
    this(request.getParams().getParams(CommonParams.FL), request);
  }

  /**
   * Builds a new {@link SolrReturnFields} with the given data.
   * The {@link CommonParams#FL} is used to get the field list(s).
   *
   * @param fl      the field list(s).
   * @param request the current request.
   */
  public SolrReturnFields(final String[] fl, final SolrQueryRequest request) {
    currentState = detectingTokenType;
    if (fl == null || fl.length == 0) {
      wantsAllFields = true;
    } else {
      try {
        parse(request, fl);
      } catch (SyntaxError exception) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, exception.getMessage(), exception);
      }
      wantsAllFields |= ((inclusions == null && inclusionGlobs == null) &&
          (exclusions == null && exclusionGlobs == null));
    }
  }

  /**
   * Processes a literal field name that will be included in response.
   * Important: calling this method cleans any previous requested exclusion list
   * (both literals and globs).
   *
   * @param expressionBuffer the char buffer that holds the literal expression.
   * @param augmenters       the accumulator for requested transformers.
   */
  void onInclusionLiteralExpression(final StringBuilder expressionBuffer, final DocTransformers augmenters,
                                    final boolean rename, final boolean isRealField) {
    final String alias = getExpressionAlias(expressionBuffer);
    final String fieldname = getExpressionValue(expressionBuffer);
    final String requestedName = (alias != null) ? alias : fieldname;

    requestedFieldNames().add(requestedName);
    inclusions().add(requestedName);

    if (SCORE.equals(fieldname)) {
      augmenters.addTransformer(new ScoreAugmenter((alias == null) ? SCORE : alias));
    } else {
      if (isRealField) {
        luceneFieldNames().add(fieldname);
        clearExclusions();
      }
      if (alias != null && rename) {
        // We can't add a RenameFieldTransformer yet because we aren't done parsing and we don't know if the field was
        // requested again separately, meaning we should copy instead of rename
        addAlias(fieldname, alias);
      }
    }
  }

  /**
   * Processes a literal value that will be excluded in response.
   * Note that if some inclusion (literal or glob) has been explicitly requested, then this method won't
   * have any effect.
   *
   * @param fieldNameBuffer the buffer that hold the exclusion literal.
   * @throws SyntaxError in case the
   */
  void onExclusionLiteralFieldName(final StringBuilder fieldNameBuffer) throws SyntaxError {
    if (CollectionUtils.isEmpty(inclusions) && CollectionUtils.isEmpty(inclusionGlobs)) {
      if (getExpressionAlias(fieldNameBuffer) == null) {
        wantsAllFields = false;
        exclusions().add(getExpressionValue(fieldNameBuffer));
      }
    }
  }

  /**
   * Processes a transformer token.
   * <p>
   * e.g. [docid]; [shard]; greeting:[value v='hello']; [mytrans5 jf=pinprojectid if=type:Projects mf=project_accessibilitytype]
   *
   * @param expressionBuffer the char buffer that holds the transformer expression.
   * @param request          the current {@link SolrQueryRequest}.
   * @param augmenters       the transformer collector for the current request.
   * @throws SyntaxError in case of invalid syntax.
   */
  void onTransformer(final StringBuilder expressionBuffer, final SolrQueryRequest request, final DocTransformers augmenters)
      throws SyntaxError {
    if (expressionBuffer != null) {
      String fl_Content = expressionBuffer.toString();
      if (fl_Content.contains("[") && fl_Content.contains("]")) {

        if (fl_Content.indexOf("[") == 0) {
          //Logic to Parse Custom Transformers containing token like (:) inside the expression

          final ModifiableSolrParams augmenterCustomArgs = new ModifiableSolrParams();
          QueryParsing.parseLocalParams(expressionBuffer.toString(), 0, augmenterCustomArgs, request.getParams(), "[", CLOSE_BRACKET);
          final String[] augmenterCustomName = augmenterCustomArgs.remove("type");
          final String customDisp = '[' + augmenterCustomName[0] + ']';
          inclusions().add(customDisp);  //TODO: This was added
          requestedFieldNames().add(customDisp);  //TODO: This was added
          final TransformerFactory customFactory = request.getCore().getTransformerFactory(augmenterCustomName[0]);
          if (customFactory != null) {
            addAugmenter(request,augmenters,augmenterCustomArgs,customDisp,customFactory);
          }
        } else if (fl_Content.indexOf("[") > 0 && fl_Content.contains(":[")) {
          //Logic to Parse In_Built Transformers

          final String alias = getExpressionAlias(expressionBuffer);
          final String transfomerExpression = getExpressionValue(expressionBuffer);
          final ModifiableSolrParams augmenterArgs = new ModifiableSolrParams();
          QueryParsing.parseLocalParams(transfomerExpression, 0, augmenterArgs, request.getParams(), "[", CLOSE_BRACKET);
          final String[] augmenterName = augmenterArgs.remove("type");
          final String aliasThatWillBeUsed = (alias != null) ? alias : OPEN_BRACKET + augmenterName[0] + CLOSE_BRACKET;
          final TransformerFactory factory = request.getCore().getTransformerFactory(augmenterName[0]);
          if (factory != null) {
            addAugmenter(request,augmenters,augmenterArgs,aliasThatWillBeUsed,factory);
            onInclusionLiteralExpression(expressionBuffer, augmenters, false, false);
          }
        }
      }
    }
  }

  private void addAugmenter(SolrQueryRequest request, DocTransformers augmenters, ModifiableSolrParams augmenterCustomArgs, String customDisp, TransformerFactory customFactory) {
    DocTransformer t = customFactory.create(customDisp, augmenterCustomArgs, request);
    if(t!=null) {
      if(!_wantsAllFields) {
        String[] extra = t.getExtraRequestFields();
        if(extra!=null) {
          Collections.addAll(luceneFieldNames(), extra);
        }
      }
      augmenters.addTransformer( t );
    }
  }

  /**
   * Processess an inclusion glob.
   * Basically here we have same rules as
   * {@link #onInclusionLiteralExpression(StringBuilder, DocTransformers, boolean, boolean)}
   * <p>
   * No aliasing is supported. No exception is raised (for backwards compatibility) but aliases are silently ignored.
   * <p>
   * That is because we don't know how many actual fields will be matched
   * by the glob expression.
   *
   * @param bufferChar the character buffer that holds the current (glob) expression.
   */
  void onInclusionGlob(final StringBuilder bufferChar) {
    final String glob = getExpressionValue(bufferChar);

    if (!ALL_FIELDS.equals(glob)) {
      inclusionGlobs().add(glob);
      clearExclusions();
    } else {
      // Special case: * is seen as an inclusion glob
      wantsAllFields = CollectionUtils.isEmpty(exclusions) && CollectionUtils.isEmpty(exclusionGlobs);
    }
  }

  @Override
  public Map<String,String> getFieldRenames() {
    return renameFields;
  }

  /**
   * Processes an exclusion glob.
   * Note that
   * <p>
   * - if some inclusion (literal or glob) has been explicitly requested, then this method won't
   * have any effect.
   * - if an alias has been specified it will be silently ignored
   * - if the expression is a * (i.e. -*) then it will be silently ignored.
   *
   * @param expressionBuffer the glob expression buffer.
   */
  void onExclusionGlobExpression(final StringBuilder expressionBuffer) {
    if (getExpressionAlias(expressionBuffer) == null) {
      final String glob = getExpressionValue(expressionBuffer);

      if (!ALL_FIELDS.equals(glob) && CollectionUtils.isEmpty(inclusions) && CollectionUtils.isEmpty(inclusionGlobs)) {
        wantsAllFields = false;
        exclusionGlobs().add(glob);
      }
    }
  }

  @Override
  public Set<String> getLuceneFieldNames() {
    return (wantsAllFields || CollectionUtils.isEmpty(luceneFieldNames)) ? null : luceneFieldNames;
  }

  @Override
  public Set<String> getLuceneFieldNames(boolean ignoreWantsAll) {
    return CollectionUtils.isEmpty(luceneFieldNames) ? null : luceneFieldNames;
  }

  @Override
  public Set<String> getRequestedFieldNames() {
    return (wantsAllFields || CollectionUtils.isEmpty(requestedFieldNames)) ? null : requestedFieldNames;
  }

  @Override
  public boolean wantsField(final String name) {
    Boolean mustInclude = cache.get(name);
    
    if (mustInclude == null) // first time request for this field
    {
      mustInclude = (inclusionRequested(name) || noFlAtAll() || haveOnlyExclusions()) && !exclusionRequested(name);
      cache.put(name, mustInclude);
    }
    return mustInclude;
  }

  private boolean noFlAtAll() {
    return exclusionGlobs == null && exclusions == null && inclusionGlobs == null && luceneFieldNames == null && inclusions == null;
  }
  private boolean haveOnlyExclusions() {
    return (exclusionGlobs != null || exclusions != null) && inclusionGlobs == null && luceneFieldNames == null && inclusions == null;
  }

  private boolean exclusionRequested(String name) {
    return (exclusions != null && exclusions.contains(name)) || (exclusionGlobs != null && wildcardMatch(name,
          exclusionGlobs));
  }

  private Boolean inclusionRequested(String name) {
    Boolean mustInclude;
    mustInclude = wantsAllFields() || (inclusions != null && inclusions.contains(name))
        || (inclusionGlobs != null && wildcardMatch(name, inclusionGlobs))
        || (luceneFieldNames != null && luceneFieldNames.contains(name));
    return mustInclude;
  }

  @Override
  public boolean wantsAllFields() {
    return wantsAllFields;
  }

  /**
   * Processes a function.
   * In case the given string cannot be processed as a valid function, then a search is done on the schema
   * in order to see if a field with such name exists.
   *
   * @param expressionBuffer the char buffer that holds the function expression.
   * @param augmenters       the transformer collector.
   * @param request          the current request.
   * @throws SyntaxError in case the function has a wrong syntax or there's no function neither a field with such name.
   */
  void onFunction(final StringBuilder expressionBuffer, final DocTransformers augmenters, final SolrQueryRequest request)
      throws SyntaxError {

    final String alias = getExpressionAlias(expressionBuffer);
    final String function = getExpressionValue(expressionBuffer);
    final QParser parser = QParser.getParser(function, FunctionQParserPlugin.NAME, request);
    Query q;

    try {
      if (parser instanceof FunctionQParser) {
        FunctionQParser fparser = (FunctionQParser) parser;
        fparser.setParseMultipleSources(false);
        fparser.setParseToEnd(false);
        q = fparser.getQuery();
      } else {
        // A QParser that's not for function queries.
        // It must have been specified via local params.
        q = parser.getQuery();
        assert parser.getLocalParams() != null;
      }

      final ValueSource vs = (q instanceof FunctionQuery) ? ((FunctionQuery) q).getValueSource() : new QueryValueSource(q, 0.0f);

      String aliasThatWillBeUsed = alias;
      if (alias == null) {
        final SolrParams localParams = parser.getLocalParams();
        if (localParams != null) {
          aliasThatWillBeUsed = localParams.get("key");
        }
      }

      aliasThatWillBeUsed = (aliasThatWillBeUsed != null) ? aliasThatWillBeUsed : function;

      // Collect the function as it would be a literal
      onInclusionLiteralExpression(expressionBuffer, augmenters, false, false);
      augmenters.addTransformer(new ValueSourceAugmenter(aliasThatWillBeUsed, parser, vs));
    } catch (SyntaxError exception) {
      if (request.getSchema().getFieldOrNull(function) != null) {
        // OK, it was an oddly named field
        onInclusionLiteralExpression(expressionBuffer, augmenters, true, true);
      } else {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
            "Error parsing fieldname: " + exception.getMessage(), exception);
      }
    }
  }

  /**
   * Builds a new {@link SolrReturnFields} with the given data.
   *
   * @param fl      the field list.
   * @param request the current request.
   */
  public SolrReturnFields(final String fl, final SolrQueryRequest request) {
    this(new String[]{fl}, request);
  }

  @Override
  public boolean wantsScore() {
    return inclusions != null && inclusions.contains(SCORE);
  }

  @Override
  public boolean hasPatternMatching() {
    return CollectionUtils.isNotEmpty(inclusionGlobs) || CollectionUtils.isNotEmpty(exclusions)
        || CollectionUtils.isNotEmpty(exclusionGlobs);
  }

  @Override
  public DocTransformer getTransformer() {
    return transformer;
  }

  /**
   * Performs a wildcard match between the given (field) name and the glob list.
   *
   * @param name  the (field) name.
   * @param globs the glob list.
   * @return true if at least one expression in the list matches the given field name.
   */
  private boolean wildcardMatch(final String name, final Set<String> globs) {
    for (final String glob : globs) {
      if (FilenameUtils.wildcardMatch(name, glob)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the expression alias found in given bufferChar.
   * The alias (if exists) is delimited by colon. If there's not such char then null is returned
   * meaning that there's no alias.
   *
   * @param bufferChar the buffer that contains the current expression.
   * @return the expression alias or null if no alias is found.
   */
  private String getExpressionAlias(final StringBuilder bufferChar) {
    final int indexOfColon = bufferChar.indexOf(ALIAS_VALUE_SEPARATOR);
    return indexOfColon == -1 ? null : bufferChar.substring(0, indexOfColon);
  }

  /**
   * Returns the expression value found in given bufferChar.
   * The value (if alias exists) starts next to colon (which is the ending char of the alias).
   * If there's no alias, all buffer content is supposed to be a value.
   *
   * @param bufferChar the buffer that contains the current expression.
   * @return the expression value.
   */
  private String getExpressionValue(final StringBuilder bufferChar) {
    final int indexOfColon = bufferChar.indexOf(ALIAS_VALUE_SEPARATOR);
    return indexOfColon == -1 ? bufferChar.toString() : bufferChar.substring(indexOfColon + 1);
  }

  /**
   * Clears any collected exclusion (both literal and globs).
   */
  private void clearExclusions() {
    if (exclusions != null) {
      exclusions.clear();
    }
    if (exclusionGlobs != null) {
      exclusionGlobs.clear();
    }
  }

  /**
   * Lazily creates the set that will hold inclusions.
   *
   * @return the set that will hold inclusions.
   */
  private Set<String> inclusions() {
    if (inclusions == null) {
      inclusions = new HashSet<>();
    }
    return inclusions;
  }

  /**
   * Lazily creates the set that will hold inclusion globs.
   *
   * @return the set that will hold inclusions globs
   */
  private Set<String> inclusionGlobs() {
    if (inclusionGlobs == null) {
      inclusionGlobs = new HashSet<>();
    }
    return inclusionGlobs;
  }

  /**
   * Lazily creates the set that will hold exclusions.
   *
   * @return the set that will hold exclusions.
   */
  private Set<String> exclusions() {
    if (exclusions == null) {
      exclusions = new HashSet<>();
    }
    return exclusions;
  }

  /**
   * Lazily creates the set that will hold exclusion globs.
   *
   * @return the set that will hold exclusion globs.
   */
  private Set<String> exclusionGlobs() {
    if (exclusionGlobs == null) {
      exclusionGlobs = new HashSet<>();
    }
    return exclusionGlobs;
  }

  /**
   * Lazily creates the set that will hold lucene field names.
   *
   * @return the set that will hold lucene field names.
   */
  private Set<String> luceneFieldNames() {
    if (luceneFieldNames == null) {
      luceneFieldNames = new HashSet<>();
    }
    return luceneFieldNames;
  }

  /**
   * Lazily creates the set that will hold requested field names.
   *
   * @return the set that will hold requested field names.
   */
  private Set<String> requestedFieldNames() {
    if (requestedFieldNames == null) {
      requestedFieldNames = new LinkedHashSet<>();
    }
    return requestedFieldNames;
  }

  /**
   * Lazily creates the map that will hold field aliases.
   *
   * @return the map that will hold the aliases.
   */
  private Map<String, List<String>> aliases() {
    if (aliases == null) {
      aliases = new HashMap<>();
    }
    return aliases;
  }

  /**
   * Add an alias to a field name
   */
  private void addAlias(String field, String alias) {
    if (aliases().containsKey(field)) {
      List<String> aliasNames = aliases().get(field);
      aliasNames.add(alias);
    } else {
      List<String> aliasNames = new ArrayList<>();
      aliasNames.add(alias);
      aliases().put(field, aliasNames);
    }
  }

  /**
   * FieldList parser state interface. Defines the behaviour that a given parser
   * state must implement.
   */
  private abstract class ParserState {

    /**
     * Callback method that lets this state process the n-th character.
     *
     * @param aChar            the char that is being processed.
     * @param expressionBuffer the char buffer shared between states. Will hold the parsed expressions.
     * @param request          the current {@link SolrQueryRequest}.
     * @param augmenters       holds transformers.
     * @throws SyntaxError in case this state detects a syntax error in the
     *                     character stream being processed.
     */
    protected abstract void onChar(char aChar, StringBuilder expressionBuffer, SolrQueryRequest request, DocTransformers augmenters)
        throws SyntaxError;

    /**
     * Switches the parser to the new given state.
     *
     * @param newState the {@link ParserState},
     */
    protected void switchTo(final ParserState newState) {
      currentState = newState;
    }

    /**
     * Resets the parser state.
     * This method resets the char buffer too.
     *
     * @param expressionBuffer the char buffer to reset.
     */
    protected void restartWithNewToken(final StringBuilder expressionBuffer) {
      expressionBuffer.setLength(0);
      currentState = detectingTokenType;
    }

    /**
     * Determines if the specified character is permissible as the first character in a field list expression.
     * A character may start a field list expression if and only if one of the following conditions is true:
     * <p>
     * <ul>
     * <li>Character.isJavaIdentifierStart(ch) returns true;</li>
     * <li>ch is pound sign, hash (#)</li>
     * </ul>
     *
     * @param ch ch the character to be tested.
     * @return true if the character may start a field list expression; false otherwise.
     */
    protected boolean isFieldListExpressionStart(final char ch) {
      return Character.isJavaIdentifierPart(ch) || ch == POUND_SIGN || ch == LEFT_BRACE;
    }

    /**
     * Determines if the specified character may be part of a field list expression as other than the first character.
     * A character may be part of a field list expression if any of the following are true:
     * <p>
     * <ul>
     * <li>Character.isJavaIdentifierPart(ch) returns true;</li>
     * <li>ch is pound sign, hash (#)</li>
     * <li>ch is colon (:)</li>
     * <li>ch is dot (.)</li>
     * </ul>
     *
     * @param ch ch the character to be tested.
     * @return true if the character can be part of a field list expression; false otherwise.
     */
    protected boolean isFieldListExpressionPart(final char ch) {
      return Character.isJavaIdentifierPart(ch) || ch == DOT || ch == COLON || ch == POUND_SIGN || ch == HYPHEN ||
          ch == PLUS || ch == LEFT_BRACE || ch == RIGHT_BRACE || ch == '!';
    }

    /**
     * Determines if the specified character may be part of a SOLR function as other than the first character.
     * A character may be part of a SOLR function if any of the following are true:
     * <p>
     * <ul>
     * <li>isSolrIdentifierPart(ch) returns true;</li>
     * <li>ch is comma (,)</li>
     * </ul>
     * <p>
     * Note that passing this method char by char doesn't mean the whole function string will be valid.
     *
     * @param ch ch the character to be tested.
     * @return true if the character may start a SOLR fieldname; false otherwise.
     */
    protected boolean isSolrFunctionPart(final char ch) {
      return isFieldListExpressionPart(ch) || ch == ',' || ch == '.' || ch == ' ' || ch == '\'' || ch == '\"' || ch == '='
          || ch == '-' || ch == '/' || ch == PLUS;
    }

    /**
     * Checks if the given buffer contains a value that is a constant.
     *
     * @param buffer the char buffer containing the current expression.
     * @return true if the given buffer contains an expression which is actually a numeric constant.
     */
    protected boolean isConstantOrFunction(final StringBuilder buffer) {
      if (buffer.indexOf("{!func") != -1) {
        return true;
      }
      int offset = buffer.lastIndexOf(ALIAS_VALUE_SEPARATOR);
      offset = (offset == -1) ? 0 : offset + 1;
      for (int i = offset; i < buffer.length(); i++) {
        final char ch = buffer.charAt(i);
        if (!(Character.isDigit(ch) || ch == HYPHEN || ch == PLUS || ch == DOT)) {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * An exclusion glob token is being parsed.
   * Activated in case an exclusion glob is detected (e.g. -pipp*,-*ippo).
   */
  final ParserState collectingExclusionGlob = new ParserState() {
    @Override
    public void onChar(final char aChar, final StringBuilder expressionBuffer, final SolrQueryRequest request,
                       final DocTransformers augmenters) throws SyntaxError {
      if (!isFieldListExpressionPart(aChar)) {
        if (aChar == WILDCARD || aChar == QUESTION_MARK) {
          expressionBuffer.append(aChar);
        }
        onExclusionGlobExpression(expressionBuffer);
        restartWithNewToken(expressionBuffer);
      } else {
        expressionBuffer.append(aChar);
      }
    }
  };

  /**
   * An exclusion (literal) token is being parsed.
   * Activated in case an exclusion literal field name is detected (e.g. -pipp*,-*ippo).
   */
  final ParserState collectingExclusionToken = new ParserState() {
    @Override
    public void onChar(final char aChar, final StringBuilder buffer, final SolrQueryRequest request,
                       final DocTransformers augmenters) throws SyntaxError {
      if (aChar == WILDCARD || aChar == QUESTION_MARK) {
        buffer.append(aChar);
        switchTo(collectingExclusionGlob);
      } else if (!isFieldListExpressionPart(aChar)) {
        if (isConstantOrFunction(buffer)) {
          onFunction(buffer, augmenters, request);
        } else {
          onExclusionLiteralFieldName(buffer);
        }
        restartWithNewToken(buffer);
      } else {
        buffer.append(aChar);
      }
    }
  };

  /**
   * A transformer token is being parsed.
   * Activated in case a transformer is detected (e.g. [docid][explain]).
   */
  final ParserState collectingTransformer = new ParserState() {
    @Override
    public void onChar(final char aChar, final StringBuilder expressionBuffer, final SolrQueryRequest request,
                       final DocTransformers augmenters) throws SyntaxError {
      expressionBuffer.append(aChar);
      if (aChar == CLOSE_BRACKET) {
        onTransformer(expressionBuffer, request, augmenters);
        restartWithNewToken(expressionBuffer);
      }
    }
  };

  /**
   * An inclusion glob token is being parsed.
   * Activated in case an inclusion glob is detected (e.g. pipp*,*ippo).
   */
  final ParserState collectingInclusionGlob = new ParserState() {
    @Override
    public void onChar(final char aChar, final StringBuilder buffer, final SolrQueryRequest request,
                       final DocTransformers augmenters) {
      if (isFieldListExpressionPart(aChar) || aChar == WILDCARD || aChar == QUESTION_MARK) {
        buffer.append(aChar);
      } else {
        onInclusionGlob(buffer);
        restartWithNewToken(buffer);
      }
    }
  };

  /**
   * A token that has been classified as function is being parsed.
   */
  final ParserState collectingFunction = new ParserState() {
    private int openParenthesis;

    @Override
    public void onChar(final char aChar, final StringBuilder expressionBuffer, final SolrQueryRequest request,
                       final DocTransformers augmenters) throws SyntaxError {
      switch (aChar) {
        case OPEN_PARENTHESIS:
          openParenthesis++;
          expressionBuffer.append(aChar);
          break;

        case CLOSE_PARENTHESIS:
          openParenthesis--;
          expressionBuffer.append(aChar);
          if (openParenthesis == 0) {
            onFunction(expressionBuffer, augmenters, request);
            restartWithNewToken(expressionBuffer);
          }
          break;
        default:
          if (isSolrFunctionPart(aChar)) {
            expressionBuffer.append(aChar);
          }
      }
    }
  };

  /**
   * A token that has been classified as function is being parsed.
   */
  final ParserState collectingLiteral = new ParserState() {
    private int quoteCount;

    @Override
    public void onChar(final char aChar, final StringBuilder expressionBuffer, final SolrQueryRequest request,
                       final DocTransformers augmenters) throws SyntaxError {
      switch (aChar) {
        case QUOTE:
        case DQUOTE:
          quoteCount++;
          expressionBuffer.append(aChar);
          if (quoteCount % 2 == 0) {
            onFunction(expressionBuffer, augmenters, request);
            restartWithNewToken(expressionBuffer);
          }
          break;
        default:
          if (isSolrFunctionPart(aChar)) {
            expressionBuffer.append(aChar);
          }
      }
    }
  };

  /**
   * Parser state activated when a token maybe several things: inclusion (literal or glob) and function.
   */
  ParserState maybeInclusionLiteralOrGlobOrFunction = new ParserState() {
    @Override
    public void onChar(final char aChar, final StringBuilder builder, final SolrQueryRequest request,
                       final DocTransformers augmenters) throws SyntaxError {
      switch (aChar) {
        case OPEN_PARENTHESIS:
          switchTo(collectingFunction);
          currentState.onChar(aChar, builder, request, augmenters);
          break;
        case QUOTE:
        case DQUOTE:
          switchTo(collectingLiteral);
          currentState.onChar(aChar, builder, request, augmenters);
          break;
        case QUESTION_MARK:
        case WILDCARD:
          builder.append(aChar);
          switchTo(collectingInclusionGlob);
          break;
        case OPEN_BRACKET:
          builder.append(aChar);
          switchTo(collectingTransformer);
          break;
        default:
          if (!isFieldListExpressionPart(aChar)) {
            if (isConstantOrFunction(builder)) {
              onFunction(builder, augmenters, request);
            } else {
              onInclusionLiteralExpression(builder, augmenters, true, true);
            }
            restartWithNewToken(builder);
          } else {
            builder.append(aChar);
          }
      }
    }
  };

  /**
   * Initial parser state: we know nothing about next (or the first) token.
   */
  final ParserState detectingTokenType = new ParserState() {
    @Override
    public void onChar(final char aChar, final StringBuilder builder, final SolrQueryRequest request,
                       final DocTransformers augmenters) throws SyntaxError {
      switch (aChar) {
        case HYPHEN:
          switchTo(collectingExclusionToken);
          break;
        case OPEN_BRACKET:
          builder.append(aChar);
          switchTo(collectingTransformer);
          break;
        case QUESTION_MARK:
        case WILDCARD:
          builder.append(aChar);
          switchTo(collectingInclusionGlob);
          break;
        case QUOTE:
        case DQUOTE:
          switchTo(collectingLiteral);
          currentState.onChar(aChar, builder, request, augmenters);
          break;
        default:
          if (isFieldListExpressionStart(aChar)) {
            builder.append(aChar);
            switchTo(maybeInclusionLiteralOrGlobOrFunction);
          }
      }
    }
  };

  /**
   * Field list parsing entry point.
   *
   * @param request    the current request.
   * @param fieldLists the field list(s) that will be parsed.
   * @throws SyntaxError in case the given field lists contains syntax errors.
   */
  void parse(final SolrQueryRequest request, final String... fieldLists) throws SyntaxError {
    final DocTransformers augmenters = new DocTransformers();
    final StringBuilder charBuffer = new StringBuilder();
    for (String fieldList : fieldLists) {
      for (int i = 0; i < fieldList.length(); i++) {
        final char aChar = fieldList.charAt(i);
        currentState.onChar(aChar, charBuffer, request, augmenters);
      }
      currentState.onChar(' ', charBuffer, request, augmenters);
    }
    for (String key : aliases().keySet()) {
      // If we've requested a field explicitly, add all aliases as copies
      if (inclusions().contains(key)) {
        for (String name : aliases.get(key)) {
          augmenters.addTransformer(new RenameFieldTransformer(key, name, true));
        }
      } else {
        // Otherwise add it as a rename
        List<String> aliasNames = aliases().get(key);
        // If we have multiple names for the field add all but the last as copies
        if (aliasNames.size() > 1) {
          for (int i = 0; i < aliasNames.size() - 1; i++) {
            augmenters.addTransformer(new RenameFieldTransformer(key, aliasNames.get(i), true));
          }
          augmenters.addTransformer(new RenameFieldTransformer(key, aliasNames.get(aliasNames.size() - 1), false));
        } else {
          augmenters.addTransformer(new RenameFieldTransformer(key, aliasNames.get(0), false));
        }
      }
      luceneFieldNames().add(key);
    }
    if (augmenters.size() == 1) {
      transformer = augmenters.getTransformer(0);
    } else if (augmenters.size() > 1) {
      transformer = augmenters;
    }
  }

  // like getId, but also accepts dashes for legacy fields
  public static String getFieldName(StrParser sp) {
    sp.eatws();
    int id_start = sp.pos;
    char ch;
    if (sp.pos < sp.end && (ch = sp.val.charAt(sp.pos)) != '$' && Character.isJavaIdentifierStart(ch)) {
      sp.pos++;
      while (sp.pos < sp.end) {
        ch = sp.val.charAt(sp.pos);
        if (!Character.isJavaIdentifierPart(ch) && ch != '.' && ch != '-') {
          break;
        }
        sp.pos++;
      }
      return sp.val.substring(id_start, sp.pos);
    }
    return null;
  }

  @Override
  public String toString() {
    return "SolrReturnFields{" +
        "requestedFieldNames=" + requestedFieldNames +
        ", wantsAllFields=" + wantsAllFields +
        ", luceneFieldNames=" + luceneFieldNames +
        ", inclusions=" + inclusions +
        ", exclusions=" + exclusions +
        ", inclusionGlobs=" + inclusionGlobs +
        ", exclusionGlobs=" + exclusionGlobs +
        ", aliases=" + aliases +
        ", transformer=" + transformer +
        '}';
  }

}
