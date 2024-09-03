package com.exalate.transform
import org.commonmark.node.*
import org.commonmark.parser.Parser
/**
 * Converts markdown to Jira / Confluence Wiki markup
 *
**/

class MdToWiki {

    String transform(String mdText) {
        Parser parser = Parser.builder().build()
        Node document = parser.parse(mdText)
        processNode(document, 0, "")
    }

    String processNode(Node node, Integer level, String listChar) {

        switch (node.class.simpleName) {
            case "Text":
                return processText((Text) node)
            case "Heading":
                return processHeading((Heading) node)
            case "Image":
                return processImage((Image) node)
            case "Emphasis":
                return processEmphasis((Emphasis) node)
            case "StrongEmphasis":
                return processStrongEmphasis((StrongEmphasis) node)
            case "BulletList":
                return processUnorderedList((BulletList) node, "", level + 1)
            case "OrderedList":
                return processOrderedList((OrderedList) node, "", level + 1)
            case "ListItem":
                return processListItem((ListItem)node, level, listChar)
            case "Code":
                return processCode((Code) node)
            case "FencedCodeBlock":
                return processFencedCodeBlock((FencedCodeBlock) node)
            case "Link":
                return processLink((Link) node)
            case "SoftLineBreak":
                return processSoftLineBreak((SoftLineBreak) node)
            case "HardLineBreak":
                return processHardLineBreak((HardLineBreak) node)
            case "Paragraph":
                return processParagraph((Paragraph) node)
            case "BlockQuote":
                return processBlockQuote((BlockQuote) node, 0)
            default:
                return processChilds(node, "", 0, "")
        }
    }

    String processChilds(Node node, String resultStr, Integer level, String listChar) {
        Node child = node.getFirstChild()
        String result = resultStr
        while(child != null) {
            result += processNode(child, level, listChar)
            child = child.next
        }
        return result
    }

    String processHeading(Heading heading) {
        String headingLabel = "h" + heading.level + ". "
        String result = processChilds(heading, "", 0, "")
        return headingLabel + result + "\n"
    }

    String processImage(Image image) {

        String sourceName = image.destination


        if (sourceName.contains("/attachments/token/")) {
            // zendesk is using direct links s to retrieve the attachment
            // the file name (which is also the local file name, is the name=<a name> parameter
            // example https://d3v-francis.zendesk.com/attachments/token/1uDApg1HQxQ3LRFKfViQmHlM2/?name=network.png

            def matcher = sourceName =~ /\?name=(\S+)/
            if (matcher.hasGroup()) {
                sourceName = matcher[0][1] as String
            }
        }
        return "!" + sourceName + "" + "|width=80%!"
    }

    String processText(Text text) {
        return text.literal
    }

    String processEmphasis(Emphasis emphasis) {
        String result = processChilds(emphasis, "",0, "")
        return "_" + result + "_"
    }

    String processStrongEmphasis(StrongEmphasis strongEmphasis) {
        String result = processChilds(strongEmphasis, "", 0, "")
        return "*" + result + "*"
    }


    /*
    **  Unordered list - the bullet list node contains the children as list items
    **  The level provides the number of indents
     */

    String processUnorderedList(BulletList bulletList, String resultStr, Integer level) {

        return ((level > 1) ? "\n" : "") +
                processChilds(bulletList, resultStr, level, "*")
    }


    String processOrderedList(OrderedList bulletList, String resultStr, Integer level) {

        return ((level > 1) ? "\n" : "") +
                processChilds(bulletList, resultStr, level, "#")
    }

    String processListItem(ListItem listItem, Integer level, String listChar) {
        // only add a new line at the end if the listItem expansion does not end with a '\n'
        String result = processChilds(listItem, "", level, listChar)
        String lastNewLine = (result.size() > 0 && result[-1] != "\n") ? "\n" : ""
        return (listChar * level) + " " + result  + lastNewLine
    }


    /*
     *  Code methods.
     *  Ensure that the code segment always starts and ends with new line
     */

    String processCode(Code code) {
        return buildCodeBlock(code.literal)
    }


    String processFencedCodeBlock(FencedCodeBlock code) {
        return buildCodeBlock(code.literal)
    }

    String buildCodeBlock(String codeBlock) {
        String firstNewLine = (codeBlock.size() > 0 && codeBlock[0] != "\n") ? "\n" : ""
        String lastNewLine = (codeBlock.size() > 1 && codeBlock[-1] != "\n") ? "\n" : ""
        return "{code}${firstNewLine}${codeBlock}${lastNewLine}{code}"

    }

    String processLink(Link link) {
        String title = processChilds(link, "", 0, "")

        return title ? "[${title}|${link.destination}]" : "[${link.destination}]"

    }

    String processSoftLineBreak(SoftLineBreak softLineBreak) {
        return " "
    }

    String processParagraph(Paragraph paragraph) {
        // ignore paragraph marks in case of following classes
        String[] ignoreNewLine =  ["BulletList", "OrderedList" ]
        String aNewLineIs = inBlockQuote(paragraph) ? "\n\n" : "\n"
        String newLineAtEnd = paragraph.next && !ignoreNewLine.contains(paragraph.next.class.simpleName) ? aNewLineIs : ""

        return processChilds(paragraph, "", 0, "") + newLineAtEnd
    }

    String processBlockQuote(BlockQuote blockQuote, Integer level) {
        String result = processChilds(blockQuote, "", 0, "")

        return "{quote}" + result + "{quote}\n"
    }

    String processHardLineBreak(HardLineBreak hardLineBreak) {
        return "\n"
    }

    // check if the node is part of a block quote

    Boolean inBlockQuote(Node node) {
        if (!node)
            return false

        if (node.class.simpleName == "BlockQuote")
            return true

        return inBlockQuote(node.parent)
    }
}
