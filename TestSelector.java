import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

public class TestSelector {
    public static void main(String[] args) throws Exception {
        Document doc = Jsoup.connect("https://www.pbc.gov.cn/fanxiqianju/135153/135173/index.html")
                .userAgent("Mozilla/5.0")
                .timeout(30000)
                .get();
        
        String[] selectors = {
            "td.unline",
            "td[height=22][align=left]",
            "td[height=\"22\"][align=\"left\"]",
            "td[height=22]",
            "td.unline, td[height=22][align=left]"
        };
        
        for (String sel : selectors) {
            Elements els = doc.select(sel);
            System.out.println(sel + " => " + els.size() + " items");
        }
    }
}
