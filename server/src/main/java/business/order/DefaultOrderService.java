package business.order;

import api.ApiException;
import business.BookstoreDbException;
import business.JdbcUtils;
import business.book.Book;
import business.book.BookDao;
import business.cart.ShoppingCart;
import business.cart.ShoppingCartItem;
import business.customer.Customer;
import business.customer.CustomerDao;
import business.customer.CustomerForm;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static java.lang.Integer.parseInt;

public class DefaultOrderService implements OrderService {

	private BookDao bookDao;
	private OrderDao orderDao;
	private CustomerDao customerDao;
	private LineItemDao lineItemDao;

	public void setBookDao(BookDao bookDao) {
		this.bookDao = bookDao;
	}
	public void setOrderDao(OrderDao orderDao) { this.orderDao = orderDao; }
	public void setCustomerDao(CustomerDao customerDao) { this.customerDao = customerDao; }
	public void setLineItemDao(LineItemDao lineItemDao) { this.lineItemDao = lineItemDao; }

	public BookDao getBookDao() { return bookDao; }
	public OrderDao getOrderDao() { return orderDao; }
	public CustomerDao getCustomerDao() { return customerDao; }
	public LineItemDao getLineItemDao() { return lineItemDao; }

	private Date getDate(String monthString, String yearString) {
		return new GregorianCalendar(Integer.parseInt(yearString), Integer.parseInt(monthString) - 1,1).getTime();
	}

	@Override
	public OrderDetails getOrderDetails(long orderId) {
		Order order = orderDao.findByOrderId(orderId);
		Customer customer = customerDao.findByCustomerId(order.getCustomerId());
		List<LineItem> lineItems = lineItemDao.findByOrderId(orderId);
		List<Book> books = lineItems
				.stream()
				.map(lineItem -> bookDao.findByBookId(lineItem.getBookId()))
				.collect(Collectors.toList());
		return new OrderDetails(order, customer, lineItems, books);
	}

	@Override
    public long placeOrder(CustomerForm customerForm, ShoppingCart cart) {

		validateCustomer(customerForm);
		validateCart(cart);

		try (Connection connection = JdbcUtils.getConnection()) {
			Date date = getDate(
					customerForm.getCcExpiryMonth(),
					customerForm.getCcExpiryYear());
			return performPlaceOrderTransaction(
					customerForm.getName(),
					customerForm.getAddress(),
					customerForm.getPhone(),
					customerForm.getEmail(),
					customerForm.getCcNumber(),
					date, cart, connection);
		} catch (SQLException e) {
			throw new BookstoreDbException("Error during close connection for customer order", e);
		}
	}

	private long performPlaceOrderTransaction(
			String name, String address, String phone,
			String email, String ccNumber, Date date,
			ShoppingCart cart, Connection connection) {
		try {
			connection.setAutoCommit(false);
			long customerId = customerDao.create(
					connection, name, address, phone, email,
					ccNumber, date);
			long customerOrderId = orderDao.create(
					connection,
					cart.getComputedSubtotal() + cart.getSurcharge(),
					generateConfirmationNumber(), customerId);
			for (ShoppingCartItem item : cart.getItems()) {
				lineItemDao.create(connection, customerOrderId,
						item.getBookId(), item.getQuantity());
			}
			connection.commit();
			return customerOrderId;
		} catch (Exception e) {
			try {
				connection.rollback();
			} catch (SQLException e1) {
				throw new BookstoreDbException("Failed to roll back transaction", e1);
			}
			return 0;
		}
	}

	private int generateConfirmationNumber(){
		Random random = new Random();
		return random.nextInt(999999999);
	}


	private void validateCustomer(CustomerForm customerForm) {

    	String name = customerForm.getName();
    	String address = customerForm.getAddress();
    	String phone = customerForm.getPhone();
    	String email = customerForm.getEmail();
    	String ccNumber = customerForm.getCcNumber();

		if (name == null || name.equals("") || name.length()<4 || name.length() > 45) {
			throw new ApiException.InvalidParameter("Invalid name field");
		}

		if (address == null || address.equals("") || address.length() < 4 || address.length() > 45) {
			throw new ApiException.InvalidParameter("Invalid address field");
		}

		if (!isPhoneValid(phone)) {
			throw new ApiException.InvalidParameter("Invalid phone field");
		}

		if (!isEmailValid(email)) {
			throw new ApiException.InvalidParameter("Invalid email field");
		}

		if(!isCcNumberValid(ccNumber)){
			throw new ApiException.InvalidParameter("Invalid Credit Card Number");
		}

		if (!expiryDateIsValid(customerForm.getCcExpiryMonth(), customerForm.getCcExpiryYear())) {
			throw new ApiException.InvalidParameter("Invalid expiry date");
		}
	}


	private boolean isPhoneValid(String phone){
		if(phone == null) return false;
		if(phone == "") return false;
		phone = phone.replaceAll(" ", "");
		phone = phone.replaceAll("-", "");
		phone = phone.replaceAll("\\(", "");
		phone = phone.replaceAll("\\)", "");
		if(!phone.matches("[\\d]+")) return false;
		if(phone.length() != 10) return false;

		return true;
	}

	private boolean isEmailValid(String email){
		if(email == null) return false;
		if(email == "") return false;
		if(email.contains(" ")) return false;
		if(!email.contains(("@"))) return false;
		if(email.charAt(email.length() - 1) == '.') return false;

		return true;
	}

	private boolean isCcNumberValid(String ccNumber){
		if(ccNumber == null) return false;
		if(ccNumber == "") return false;
		ccNumber = ccNumber.replaceAll(" ", "");
		ccNumber = ccNumber.replaceAll("-", "");

		if(ccNumber.length() <14 || ccNumber.length() >16) return false;

		return true;
	}

	private boolean expiryDateIsValid(String ccExpiryMonth, String ccExpiryYear) {

		if(ccExpiryMonth == null || "".equals(ccExpiryMonth))
			return true;
		if(ccExpiryYear == null || "".equals(ccExpiryYear))
			return true;

		YearMonth expiryDate = YearMonth.of(parseInt(ccExpiryYear), parseInt(ccExpiryMonth));
		YearMonth current = YearMonth.now();

		if(current.compareTo(expiryDate) > 0) return false;

		return true;
	}

	private void validateCart(ShoppingCart cart) {

		if (cart.getItems().size() <= 0) {
			throw new ApiException.InvalidParameter("Cart is empty.");
		}

		cart.getItems().forEach(item-> {
			if (item.getQuantity() < 0 || item.getQuantity() > 99) {
				throw new ApiException.InvalidParameter("Invalid quantity");
			}
			Book databaseBook = bookDao.findByBookId(item.getBookId());
			if(!(item.getBookForm().getPrice() == databaseBook.getPrice())){
				throw new ApiException.InvalidParameter("Price of books are mismatched.");
			}
			if(!(item.getBookForm().getCategoryId() == databaseBook.getCategoryId())){
				throw new ApiException.InvalidParameter("Category of books are mismatched.");
			}
		});
	}

}
