package asset.pipeline.grails


import asset.pipeline.AssetHelper
import asset.pipeline.AssetPipeline
import org.codehaus.groovy.grails.web.mime.MimeType
import org.codehaus.groovy.grails.web.mime.MimeUtility
import org.codehaus.groovy.grails.web.util.GrailsPrintWriter
import org.springframework.context.ApplicationContextAware
import org.springframework.core.io.Resource


class AssetsTagLib {

    static namespace = 'asset'
    static returnObjectForTags = ['assetPath']

    private static final LINE_BREAK = System.getProperty('line.separator') ?: '\n'


    AssetProcessorService assetProcessorService
    AssetResourceLocator assetResourceLocator
    ApplicationContextAware grailsApplication
    MimeUtility grailsMimeUtility


    /**
     * @attr src REQUIRED
     */
    def javascript = {final attrs ->
        final GrailsPrintWriter outPw = out
        attrs.remove('href')
        element(attrs, 'js', 'application/javascript', null) {final String src, final String queryString, final outputAttrs, final String endOfLine ->
            outPw << '<script type="text/javascript" src="' << assetPath(src: src) << queryString << '" ' << paramsToHtmlAttr(outputAttrs) << '></script>' << endOfLine
        }
    }

    /**
     * At least one of {@code href} and {@code src} must be supplied
     *
     * @attr href OPTIONAL standard URL attribute
     * @attr src  OPTIONAL alternate URL attribute, only used if {@code href} isn't supplied, or if {@code href} is Groovy false
     */
    def stylesheet = {final attrs ->
        final GrailsPrintWriter outPw = out
        element(attrs, 'css', 'text/css', Objects.toString(attrs.remove('href'), null)) {final String src, final String queryString, final outputAttrs, final String endOfLine ->
            if (endOfLine) {
                outPw << '<link rel="stylesheet" href="' << assetPath(src: src) << queryString << '" ' << paramsToHtmlAttr(outputAttrs) << '/>' << endOfLine
            }
            else {
                outPw << link([rel: 'stylesheet', href: src] + outputAttrs)
            }
        }
    }

    private void element(final attrs, final String ext, final String contentType, final String srcOverride, final Closure<GrailsPrintWriter> output) {
        def src = attrs.remove('src')
        if (srcOverride) {
            src = srcOverride
        }
        src = "${AssetHelper.nameWithoutExtension(src)}.${ext}"

        final def nonBundledMode = (!grailsApplication.warDeployed && grailsApplication.config.grails.assets.bundle != true && attrs.remove('bundle') != 'true')
        if (! nonBundledMode) {
            output(src, '', attrs, '')
        }
        else {
            final int lastDotIndex = src.lastIndexOf('.')
            final def uri
            final def extension
            if (lastDotIndex >= 0) {
                uri       = src.substring(0, lastDotIndex)
                extension = src.substring(lastDotIndex + 1)
            }
            else {
                uri       = src
                extension = ext
            }
            final String queryString =
                attrs.charset \
                    ? "?compile=false&encoding=${attrs.charset}"
                    : '?compile=false'
            AssetPipeline.getDependencyList(uri, contentType, extension)?.each {
                output(it.path, queryString, attrs, LINE_BREAK)
            }
        }
    }

    def image = {attrs ->
        def src = attrs.remove('src')
        def absolute = attrs.remove('absolute')
        out << "<img src=\"${assetPath(src:src, absolute: absolute)}\" ${paramsToHtmlAttr(attrs)}/>"
    }


    /**
     * @attr href REQUIRED
     * @attr rel REQUIRED
     * @attr type OPTIONAL
     */
    def link = {attrs ->
        def href = attrs.remove('href')
        out << "<link ${paramsToHtmlAttr(attrs)} href=\"${assetPath(src:href)}\"/>"
    }


    def script = {attrs, body ->
        def assetBlocks = request.getAttribute('assetScriptBlocks')
        if (!assetBlocks) {
            assetBlocks = []
        }
        assetBlocks << [attrs: attrs, body: body()]
        request.setAttribute('assetScriptBlocks', assetBlocks)
    }

    def deferredScripts = {attrs ->
        def assetBlocks = request.getAttribute('assetScriptBlocks')
        if (!assetBlocks) {
            return
        }
        assetBlocks.each {assetBlock ->
            out << "<script ${paramsToHtmlAttr(assetBlock.attrs)}>${assetBlock.body}</script>"
        }
    }


    def assetPath = {attrs ->
        g.assetPath(attrs)
    }

    def assetPathExists = {attrs, body ->
        if (isAssetPath(attrs.remove('src'))) {
            out << (body() ?: true)
        }
        else {
            out << ''
        }
    }

    Closure inlineStylesheet = { Map attrs ->
        final GrailsPrintWriter outPw = out
        Resource asset = assetResourceLocator.findAssetForURI(attrs.remove('src') as String)
        outPw << '<style type="text/css" ' << paramsToHtmlAttr(attrs) << '>'  << expandContents(asset.inputStream.text) << '</style>'
    }

    Closure inlineJavascript = { Map attrs ->
        final GrailsPrintWriter outPw = out
        Resource asset = assetResourceLocator.findAssetForURI(attrs.remove('src') as String)

        attrs.remove('href')
        outPw << '<script type="text/javascript"' << paramsToHtmlAttr(attrs) << '>' << asset.inputStream.text << '</script>'
    }

    Closure inlineImage = { Map attrs ->
        String src = attrs.remove('src')
        String absolute = attrs.remove('absolute')
        out << "<img src=\"${getDataURI(src)}\" ${paramsToHtmlAttr(attrs)}/>"
    }

    boolean isAssetPath(String src) {
        assetProcessorService.isAssetPath(src)
    }

    String getDataURI(String uri, String mime = null){
        Resource asset = assetResourceLocator.findAssetForURI(uri)
        MimeType mimeType = grailsMimeUtility.getMimeTypeForURI(uri)
        mime = mime ?: mimeType?.name
        if(mime){
            mime+=';'
        } else {
            //throw warning. Most things work without MIME type, but not all
        }

        "data:${mime?:''}base64,${asset.inputStream.bytes.encodeBase64()}"

    }

    String expandContents(String contents) {
        contents.replaceAll(~/url\((['"])([^'"]+)\1\)/) { String entireMatch, String quoteType, String uri  ->

            //TODO: Find a better way to clean the url
            uri =  uri.replaceAll(~/\?.*\u0024|#.*\u0024|\.\.?\//, '') //remove extraneous stuff from the url

            if(isAssetPath(uri)){
                "url('${getDataURI(uri)}')"
            } else {
                entireMatch
            }
        }
    }

    private paramsToHtmlAttr(Map attrs) {
        attrs.collect {key, value -> "${key}=\"${value.toString().replace('"', '\\"')}\""}?.join(' ')
    }
}
