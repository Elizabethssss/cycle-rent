package com.belikov.valteris.cycle.bicycle;

import com.belikov.valteris.cycle.bicycle.model.BicycleDTO;
import com.belikov.valteris.cycle.bicycle.model.BicycleType;
import com.belikov.valteris.cycle.bicycle.model.SortType;
import com.belikov.valteris.cycle.detail.DetailService;
import com.belikov.valteris.cycle.order.OrderService;
import com.belikov.valteris.cycle.order.model.OrderDTO;
import com.belikov.valteris.cycle.order.model.OrderStatus;
import com.belikov.valteris.cycle.order_bicycle.OrderBicycleService;
import com.belikov.valteris.cycle.order_bicycle.model.OrderBicycleDTO;
import com.belikov.valteris.cycle.user.UserService;
import com.belikov.valteris.cycle.user.model.UserDTO;
import lombok.AllArgsConstructor;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
@SessionAttributes({"userDTO", "inBasket"})
@AllArgsConstructor(onConstructor = @__(@Autowired))
public class BicycleController {

    private final BicycleService bicycleService;
    private final UserService userService;
    private final OrderService orderService;
    private final DetailService detailService;
    private final OrderBicycleService orderBicycleService;

    // В сессии для сохранения юзера и заказов (хз как по другому мона)
    @ModelAttribute(name = "userDTO")
    public UserDTO userDTO() {
        return new UserDTO();
    }

    @ModelAttribute(name = "inBasket")
    public int inBasket() {
        return 0;
    }

    @GetMapping("/bicycles")
    public String bicyclesListPage() {
        return "bicycle-list";
    }

    @GetMapping("/bicycles/all/sort/{typeOfSort}/type/{bicycleType}/page/{numberOfPage}")
    @ResponseBody
    public String getSortedPageOfBicycles(@PathVariable String typeOfSort,
                                          @PathVariable String bicycleType, @PathVariable int numberOfPage) {
        final Page<BicycleDTO> bicyclePage = bicycleService.findSortedPage(SortType.valueOf(typeOfSort),
                BicycleType.valueOf(bicycleType), numberOfPage);
        final int totalPages = bicyclePage.getTotalPages();
        numberOfPage = checkNumberOfPage(numberOfPage, totalPages);

        return getJson(numberOfPage, bicyclePage, totalPages);
    }

    @GetMapping("/index")
    public String showHomePage(RedirectAttributes attributes, @ModelAttribute("userDTO") UserDTO userDTO, Model model) {
        final Optional<OrderDTO> formedOrder = orderService.findByUserDTOAndStatus(userDTO, OrderStatus.FORMED);
        if (!formedOrder.isPresent()) {
            createNewOrder(userDTO);
        }

        model.addAttribute("userDTO", userDTO);
//        attributes.addFlashAttribute("userDTO", userDTO);
        return "index";
    }

    @PostMapping("/index")
    public String performLogin(@ModelAttribute UserDTO user, RedirectAttributes attributes) {
        user = userService.findByUsername(user.getUsername()).orElse(null);
        attributes.addFlashAttribute("userDTO", user);
        return "redirect:/index";
    }

    @PostMapping("/bicycleName")
    @ResponseBody
    public String getBicycleNames(@RequestBody String example) {
        final Page<BicycleDTO> bicyclePage = bicycleService.getBicyclesLike(example);
        int totalPages = bicyclePage.getTotalPages();
        if (totalPages == 0) {
            totalPages = 1;
        }

        return getJson(1, bicyclePage, totalPages);
    }

    @GetMapping("/bicycle/{id}")
    public String bicyclePage(@PathVariable Long id, Model model) {
        model.addAttribute("bicycle", bicycleService.getById(id).orElse(null));
        return "bicycle-page";
    }

    @GetMapping("/api/bicycle/{id}")
    @ResponseBody
    public BicycleDTO getBicycle(@PathVariable Long id) {
        return bicycleService.getById(id).get();
    }

    @PostMapping(value = "/bicycle/totalPrice", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String getTotalPrice(@RequestBody String data) {
        double totalValue = countTotalValue(data);
        JSONObject json = new JSONObject();
        json.put("totalValue", totalValue);
        return json.toString();
    }

    @PostMapping(value = "/bicycle/addToBasket", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String addBicycleToBasket(@RequestBody String data, @ModelAttribute("userDTO") UserDTO userDTO,
                                     RedirectAttributes attributes) {
        JSONObject jsonData = new JSONObject(data);
        final long bicycleId = jsonData.getLong("bicycleId");
        final Optional<BicycleDTO> bicycleDTO = bicycleService.getById(bicycleId);
        final Optional<OrderDTO> formedOrder = orderService.findByUserDTOAndStatus(userDTO, OrderStatus.FORMED);

        saveOrderBicycleDTO(jsonData, bicycleDTO, formedOrder);

        final Map<String, Object> detailPrice = jsonData.getJSONObject("optionPrice").toMap();
        for (String detail : detailPrice.keySet()) {
            final Long detailId = Long.valueOf(detail.substring(5));
            formedOrder.ifPresent(orderDTO -> orderDTO.getDetailDTOS().add(detailService.getById(detailId).get()));
        }
        formedOrder.ifPresent(orderService::save);

        JSONObject json = new JSONObject();

        return json.toString();
    }

    @PostMapping("/bicycle/create")
    @ResponseStatus(HttpStatus.CREATED)
    public void add(@RequestBody BicycleDTO newBicycle) {
        bicycleService.save(newBicycle);
    }

    @DeleteMapping("/bicycle/delete/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        bicycleService.delete(id);
    }

    private int checkNumberOfPage(int numberOfPage, int totalPages) {
        if (numberOfPage < 1 || numberOfPage > totalPages) {
            numberOfPage = 1;
        }
        return numberOfPage;
    }

    private void createNewOrder(UserDTO userDTO) {
        final OrderDTO order = new OrderDTO();
        order.setStatus(OrderStatus.FORMED);
        order.setUserDTO(userDTO);
        order.setDetailDTOS(new ArrayList<>());
        orderService.save(order);
    }

    private void saveOrderBicycleDTO(JSONObject jsonData, Optional<BicycleDTO> bicycleDTO,
                                                Optional<OrderDTO> formedOrder) {
        final OrderBicycleDTO orderBicycleDTO = new OrderBicycleDTO();
        formedOrder.ifPresent(orderBicycleDTO::setOrderDTO);
        bicycleDTO.ifPresent(orderBicycleDTO::setBicycleDTO);
        orderBicycleDTO.setTimeStart(getLocalTime(jsonData.getDouble("start")));
        orderBicycleDTO.setTimeEnd(getLocalTime(jsonData.getDouble("end")));
        orderBicycleService.save(orderBicycleDTO);
    }

    private double countTotalValue(@RequestBody String data) {
        JSONObject jsonData = new JSONObject(data);
        final long bicycleId = jsonData.getLong("bicycleId");
        final Optional<BicycleDTO> bicycleDTO = bicycleService.getById(bicycleId);

        double totalValue = 0;
        if (bicycleDTO.isPresent()) {
            double timeDifference = jsonData.getDouble("end") - jsonData.getDouble("start");
            totalValue = bicycleDTO.get().getPrice() * timeDifference;
        }
        final Map<String, Object> optionPrice = jsonData.getJSONObject("optionPrice").toMap();
        for (Object price : optionPrice.values()) {
            totalValue += (Integer) price;
        }
        return totalValue;
    }

    private LocalTime getLocalTime(double time) {
        final int hours = (int) time;
        int minutes = 0;
        if (time - hours != 0) {
            minutes = 30;
        }
        return LocalTime.of(hours, minutes);
    }

    private String getJson(@PathVariable int numberOfPage, Page<BicycleDTO> bicyclePage, int totalPages) {
        JSONObject json = new JSONObject();
        json.put("currentPage", numberOfPage);
        json.put("totalPages", totalPages);
        json.put("bicycles", bicyclePage.get().collect(Collectors.toList()));

        return json.toString();
    }
}
