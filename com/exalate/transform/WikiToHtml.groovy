package com.exalate.transform

import com.atlassian.jira.component.ComponentAccessor


/**
* Class to transform from wiki notation to html
* This version will replace inline images with a note that there is one
*/

class WikiToHtml {
	static String transform(String wikiFormat) {
		if (!wikiFormat) {
			return null
		}

		// access the correct services
		def jcl = ComponentAccessor.classLoader
		def app = ComponentAccessor.getApplicationProperties()
		def epubClass = jcl.loadClass("com.atlassian.event.api.EventPublisher")
		def epub = ComponentAccessor.getOSGiComponentInstanceOfType(epubClass)
		def fmanClass = jcl.loadClass("com.atlassian.jira.config.FeatureManager")
		def fman = ComponentAccessor.getOSGiComponentInstanceOfType(fmanClass)
		def vreqClass = jcl.loadClass("com.atlassian.jira.util.velocity.VelocityRequestContextFactory")
		def vreq = ComponentAccessor.getOSGiComponentInstanceOfType(vreqClass)
		def wrenderClass = jcl.loadClass("com.atlassian.jira.issue.fields.renderer.wiki.AtlassianWikiRenderer")
		def wrender = wrenderClass.newInstance(epub, app, vreq, fman)


		def fixImage = wikiFormat?.replaceAll(/\!(\S+)\|\S+\!/, '<!-- inline image filename=#$1# -->')
		fixImage = fixImage.replaceAll(/\!\^(\S+)\|\S+\!/, '<!-- inline image filename=#$1# -->')
		fixImage = fixImage.replaceAll(/\!\^(\S+)\!/, '<!-- inline image filename=#$1# -->')
		fixImage = fixImage.replaceAll(/\!(\S+)\!/, '<!-- inline image filename=#$1# -->')

		// wiki text can also contain files
		fixImage = fixImage.replaceAll(/\[(\S+)\|\^(\S+)\]/, '<!-- inline file filename=#$2# -->')
		fixImage = fixImage.replaceAll(/\[\^(\S+)\]/, '<!-- inline file filename=#$1# -->')
		return wrender.render(fixImage, null)

	}

}
