package brs.http
import brs.util.Subnet
import brs.util.convert.nullToEmpty
import brs.util.logging.safeDebug
import org.owasp.encoder.Encode
import org.slf4j.LoggerFactory
import java.io.IOException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class APITestServlet(apiServlet: APIServlet, private val allowedBotHosts: Set<Subnet>?) : HttpServlet() {
    private val requestTypes: List<String>
    private val apiRequestHandlers = apiServlet.apiRequestHandlers
    private val requestTags: Map<String, Set<String>>

    init {
        requestTags = buildRequestTags()
        requestTypes = apiRequestHandlers.keys.sorted()
    }

    private fun buildRequestTags(): Map<String, Set<String>> {
        val r = mutableMapOf<String, MutableSet<String>>()
        for ((requestType, value) in apiRequestHandlers) {
            val apiTags = value.apiTags
            for (apiTag in apiTags) {
                val set = r.computeIfAbsent(apiTag.name) { mutableSetOf() }
                set.add(requestType)
            }
        }
        return r
    }

    private fun buildLinks(request: HttpServletRequest): String {
        val buf = StringBuilder()
        val requestTag = request.getParameter("requestTag").nullToEmpty()
        buf.append("<li")
        if (requestTag.isEmpty()) {
            buf.append(" class=\"active\"")
        }
        buf.append("><a href=\"/test\">All</a></li>")
        for (apiTag in APITag.values()) {
            if (requestTags[apiTag.name] != null) {
                buf.append("<li")
                if (requestTag == apiTag.name) {
                    buf.append(" class=\"active\"")
                }
                buf.append("><a href=\"/test?requestTag=").append(apiTag.name).append("\">")
                buf.append(apiTag.displayName).append("</a></li>").append(" ")
            }
        }
        return buf.toString()
    }

    override fun doGet(request: HttpServletRequest, resp: HttpServletResponse) {
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate, private")
        resp.setHeader("Pragma", "no-cache")
        resp.setDateHeader("Expires", 0)
        resp.contentType = "text/html; charset=UTF-8"

        if (allowedBotHosts != null && !allowedBotHosts.toString().contains(request.remoteHost)) {
            try {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN)
            } catch (e: IOException) {
                logger.safeDebug(e) { "IOException: " }
            }

            return
        }

        try {
            resp.writer.use { writer ->
                writer.print(HEADER_1)
                writer.print(buildLinks(request))
                writer.print(HEADER_2)
                val requestType = Encode.forHtml(request.getParameter("requestType")).nullToEmpty()
                var requestHandler: APIServlet.HttpRequestHandler? = apiRequestHandlers[requestType]
                val bufJSCalls = StringBuilder()
                if (requestHandler != null) {
                    writer.print(form(requestType, true, requestHandler.javaClass.name, requestHandler.parameters, requestHandler.requirePost()))
                    bufJSCalls.append("apiCalls.push(\"").append(requestType).append("\");\n")
                } else {
                    val requestTag = request.getParameter("requestTag").nullToEmpty()
                    val taggedTypes = requestTags[requestTag]
                    for (type in taggedTypes ?: requestTypes) {
                        requestHandler = apiRequestHandlers[type]
                        writer.print(form(type, false, requestHandler!!.javaClass.name, apiRequestHandlers[type]!!.parameters, apiRequestHandlers[type]!!.requirePost()))
                        bufJSCalls.append("apiCalls.push(\"").append(type).append("\");\n")
                    }
                }
                writer.print(FOOTER_1)
                writer.print(bufJSCalls.toString())
                writer.print(FOOTER_2)
            }
        } catch (e: IOException) {
            logger.safeDebug(e) { "IOException: " }
        }
    }

    private fun form(requestType: String, singleView: Boolean, className: String, parameters: List<String>, requirePost: Boolean): String {
        val buf = StringBuilder()
        buf.append("<div class=\"panel panel-default api-call-All\" ")
        buf.append("id=\"api-call-").append(requestType).append("\">")
        buf.append("<div class=\"panel-heading\">")
        buf.append("<h4 class=\"panel-title\">")
        buf.append("<a data-toggle=\"collapse\" class=\"collapse-link\" data-target=\"#collapse").append(requestType).append("\" href=\"#\">")
        buf.append(requestType)
        buf.append("</a>")
        buf.append("<span style=\"float:right;font-weight:normal;font-size:14px;\">")
        if (!singleView) {
            buf.append("<a href=\"/test?requestType=").append(requestType)
            buf.append("\" target=\"_blank\" style=\"font-weight:normal;font-size:14px;color:#777;\"><span class=\"glyphicon glyphicon-new-window\"></span></a>")
            buf.append(" &nbsp;&nbsp;")
        }
        buf.append("<a style=\"font-weight:normal;font-size:14px;color:#777;\" href=\"/doc/")
        buf.append(className.replace('.', '/')).append(".html\" target=\"_blank\">javadoc</a>")
        buf.append("</span>")
        buf.append("</h4>")
        buf.append("</div>")
        buf.append("<div id=\"collapse").append(requestType).append("\" class=\"panel-collapse collapse")
        if (singleView) {
            buf.append(" in")
        }
        buf.append("\">")
        buf.append("<div class=\"panel-body\">")
        buf.append("<form action=\"/burst\" method=\"POST\" onsubmit=\"return submitForm(this);\">")
        buf.append("<input type=\"hidden\" name=\"requestType\" value=\"").append(requestType).append("\"/>")
        buf.append("<div class=\"col-xs-12 col-lg-6\" style=\"width: 40%;\">")
        buf.append("<table class=\"table\">")
        for (parameter in parameters) {
            buf.append("<tr>")
            buf.append("<td>").append(parameter).append(":</td>")
            buf.append("<td><input type=\"")
            buf.append(if ("secretPhrase" == parameter) "password" else "text")
            buf.append("\" name=\"").append(parameter).append("\" style=\"width:100%;min-width:200px;\"/></td>")
            buf.append("</tr>")
        }
        buf.append("<tr>")
        buf.append("<td colspan=\"2\"><input type=\"submit\" class=\"btn btn-default\" value=\"submit\"/></td>")
        buf.append("</tr>")
        buf.append("</table>")
        buf.append("</div>")
        buf.append("<div class=\"col-xs-12 col-lg-6\" style=\"min-width: 60%;\">")
        buf.append("<h5 style=\"margin-top:0px;\">")
        if (!requirePost) {
            buf.append("<span style=\"float:right;\" class=\"uri-link\">")
            buf.append("</span>")
        } else {
            buf.append("<span style=\"float:right;font-size:12px;font-weight:normal;\">POST only</span>")
        }
        buf.append("Response</h5>")
        buf.append("<pre class=\"result\">JSON response</pre>")
        buf.append("</div>")
        buf.append("</form>")
        buf.append("</div>")
        buf.append("</div>")
        buf.append("</div>")
        return buf.toString()
    }

    companion object {

        private val logger = LoggerFactory.getLogger(APITestServlet::class.java)

        private const val HEADER_1 = (
                "<!DOCTYPE html>\n"
                        + "<html>\n"
                        + "<head>\n"
                        + "    <meta charset=\"UTF-8\"/>\n"
                        + "    <meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">"
                        + "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                        + "    <title>Burst http API</title>\n"
                        + "    <link href=\"css/bootstrap.min.css\" rel=\"stylesheet\" type=\"text/css\" />"
                        + "    <style type=\"text/css\">\n"
                        + "        table {border-collapse: collapse;}\n"
                        + "        td {padding: 10px;}\n"
                        + "        .result {white-space: pre; font-family: monospace; overflow: auto;}\n"
                        + "    </style>\n"
                        + "    <script type=\"text/javascript\">\n"
                        + "        var apiCalls;\n"
                        + "        function performSearch(searchStr) {\n"
                        + "            if (searchStr == '') {\n"
                        + "              $('.api-call-All').show();\n"
                        + "            } else {\n"
                        + "              $('.api-call-All').hide();\n"
                        + "              $('.topic-link').css('font-weight', 'normal');\n"
                        + "              for(var i=0; i<apiCalls.length; i++) {\n"
                        + "                var apiCall = apiCalls[i];\n"
                        + "                if (new RegExp(searchStr.toLowerCase()).test(apiCall.toLowerCase())) {\n"
                        + "                  $('#api-call-' + apiCall).show();\n"
                        + "                }\n"
                        + "              }\n"
                        + "            }\n"
                        + "        }\n"
                        + "        function submitForm(form) {\n"
                        + "            var url = '/burst';\n"
                        + "            for (i = 0; i < form.elements.length; i++) {\n"
                        + "                if (form.elements[i].type != 'button' && form.elements[i].value && form.elements[i].value != 'submit') {\n"
                        + "                    url += ((i == 0 && '?') || '&') + form.elements[i].name + '=' + form.elements[i].value;\n"
                        + "                }\n"
                        + "            }\n"
                        + "            $.ajax({\n"
                        + "                url: url,\n"
                        + "                type: 'POST',\n"
                        + "            })\n"
                        + "            .done(function(result) {\n"
                        + "                var resultStr = JSON.stringify(JSON.parse(result), null, 4);\n"
                        + "                form.getElementsByClassName(\"result\")[0].textContent = resultStr;\n"
                        + "            })\n"
                        + "            .fail(function() {\n"
                        + "                alert('API not available, check if Burst Server is running!');\n"
                        + "            });\n"
                        + "            if ($(form).has('.uri-link').length > 0) {\n"
                        + "                  var html = '<a href=\"' + url + '\" target=\"_blank\" style=\"font-size:12px;font-weight:normal;\">Open GET URL</a>';"
                        + "                  form.getElementsByClassName(\"uri-link\")[0].innerHTML = html;\n"
                        + "            }"
                        + "            return false;\n"
                        + "        }\n"
                        + "    </script>\n"
                        + "</head>\n"
                        + "<body>\n"
                        + "<div class=\"navbar navbar-default\" role=\"navigation\">"
                        + "   <div class=\"container\" style=\"min-width: 90%;\">"
                        + "       <div class=\"navbar-header\">"
                        + "           <a class=\"navbar-brand\" href=\"/test\">Burst http API</a>"
                        + "       </div>"
                        + "       <div class=\"navbar-collapse collapse\">"
                        + "           <ul class=\"nav navbar-nav navbar-right\">"
                        + "               <li><input type=\"text\" class=\"form-control\" id=\"search\" "
                        + "                    placeholder=\"Search\" style=\"margin-top:8px;\"></li>\n"
                        + "               <li><a href=\"https://burstwiki.org/en/the-burst-api/\" target=\"_blank\" style=\"margin-left:20px;\">Wiki Docs</a></li>"
                        + "               <li><a href=\"/doc/index.html\" target=\"_blank\" style=\"margin-left:20px;\">Javadoc Index</a></li>"
                        + "           </ul>"
                        + "       </div>"
                        + "   </div>"
                        + "</div>"
                        + "<div class=\"container\" style=\"min-width: 90%;\">"
                        + "<div class=\"row\">"
                        + "  <div class=\"col-xs-12\" style=\"margin-bottom:15px;\">"
                        + "    <div class=\"pull-right\">"
                        + "      <a href=\"#\" id=\"navi-show-open\">Show Open</a>"
                        + "       | "
                        + "      <a href=\"#\" id=\"navi-show-all\" style=\"font-weight:bold;\">Show All</a>"
                        + "    </div>"
                        + "  </div>"
                        + "</div>"
                        + "<div class=\"row\" style=\"margin-bottom:15px;\">"
                        + "  <div class=\"col-xs-4 col-sm-3 col-md-2\">"
                        + "    <ul class=\"nav nav-pills nav-stacked\">")
        private const val HEADER_2 = (
                "    </ul>"
                        + "  </div>"
                        + "  <div  class=\"col-xs-8 col-sm-9 col-md-10\">"
                        + "    <div class=\"panel-group\" id=\"accordion\">")

        private const val FOOTER_1 = (
                "    </div> "
                        + "  </div> "
                        + "</div> "
                        + "</div> "
                        + "<script src=\"js/3rdparty/jquery.min.js\" integrity=\"sha384-tsQFqpEReu7ZLhBV2VZlAu7zcOV+rXbYlF2cqB8txI/8aZajjp4Bqd+V6D5IgvKT\"></script>"
                        + "<script src=\"js/3rdparty/bootstrap.min.js\" integrity=\"sha384-Tc5IQib027qvyjSMfHjOMaLkfuWVxZxUPnCJA7l2mCWNIpG9mGCD8wGNIcPD7Txa\" type=\"text/javascript\"></script>"
                        + "<script>"
                        + "  $(document).ready(function() {"
                        + "    apiCalls = [];\n")

        private const val FOOTER_2 = (
                "    $(\".collapse-link\").click(function(event) {"
                        + "       event.preventDefault();"
                        + "    });"
                        + "    $('#search').keyup(function(e) {\n"
                        + "      if (e.keyCode == 13) {\n"
                        + "        performSearch($(this).val());\n"
                        + "      }\n"
                        + "    });\n"
                        + "    $('#navi-show-open').click(function(e) {"
                        + "      $('.api-call-All').each(function() {"
                        + "        if($(this).find('.panel-collapse.in').length != 0) {"
                        + "          $(this).show();"
                        + "        } else {"
                        + "          $(this).hide();"
                        + "        }"
                        + "      });"
                        + "      $('#navi-show-all').css('font-weight', 'normal');"
                        + "      $(this).css('font-weight', 'bold');"
                        + "      e.preventDefault();"
                        + "    });"
                        + "    $('#navi-show-all').click(function(e) {"
                        + "      $('.api-call-All').show();"
                        + "      $('#navi-show-open').css('font-weight', 'normal');"
                        + "      $(this).css('font-weight', 'bold');"
                        + "      e.preventDefault();"
                        + "    });"
                        + "  });"
                        + "</script>"
                        + "</body>\n"
                        + "</html>\n")
    }
}
